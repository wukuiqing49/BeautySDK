"""本地SDK发布制品结构检查，供候选打包调用；不执行Gradle或应用。"""
import hashlib
import io
import json
import xml.etree.ElementTree as ET
import zipfile


def validate_publication(module, version, artifacts):
    """artifacts为文件名到原始字节。失败拒绝打包，不修复或改写制品。"""
    prefix = f'{module}-{version}'
    namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
    pom = ET.fromstring(artifacts[prefix + '.pom'])
    for field, expected in [('groupId', 'com.beautysdk'), ('artifactId', module), ('version', version), ('packaging', 'aar')]:
        if pom.findtext('m:' + field, namespaces=namespace) != expected:
            raise ValueError(f'{module}: unexpected POM {field}')
    dependencies = []
    allowed_sdk = {'lib_beauty', 'lib_beauty_resources', 'lib_beauty_mediapipe', 'lib_beauty_gpupixel'} if module == 'beautysdk' else {'lib_beauty'}
    for dependency in pom.findall('m:dependencies/m:dependency', namespace):
        group = dependency.findtext('m:groupId', namespaces=namespace)
        artifact = dependency.findtext('m:artifactId', namespaces=namespace)
        dep_version = dependency.findtext('m:version', namespaces=namespace)
        dependencies.append((group, artifact, dep_version))
        if group not in {'com.beautysdk', 'com.google.mediapipe', 'org.jetbrains.kotlin'}:
            raise ValueError(f'{module}: unreviewed direct dependency {group}:{artifact}')
        if group == 'com.beautysdk' and (artifact not in allowed_sdk or dep_version != version):
            raise ValueError(f'{module}: mismatched SDK dependency')
        if module == 'beautysdk' and group == 'com.beautysdk' and dependency.findtext('m:scope', namespaces=namespace) != 'compile':
            raise ValueError('beautysdk: SDK APIs must be exported to consumers')
    if module == 'beautysdk' and {a for g, a, v in dependencies if g == 'com.beautysdk'} != allowed_sdk:
        raise ValueError('beautysdk: unified entry must export all four SDK modules')
    if module != 'lib_beauty' and ('com.beautysdk', 'lib_beauty', version) not in dependencies:
        raise ValueError(f'{module}: missing shared core dependency')
    metadata = json.loads(artifacts[prefix + '.module'])
    component = metadata.get('component', {})
    if (component.get('group'), component.get('module'), component.get('version')) != ('com.beautysdk', module, version):
        raise ValueError(f'{module}: Gradle component mismatch')
    references = set()
    metadata_dependencies = set()
    consumer_usages = set()
    for variant in metadata.get('variants', []):
        attributes = variant.get('attributes', {})
        if module == 'beautysdk' and attributes.get('org.gradle.category') == 'library' and attributes.get('org.gradle.usage') in {'java-api', 'java-runtime'}:
            consumer_usages.add(attributes['org.gradle.usage'])
            sdk_deps = {(d.get('module'), d.get('version', {}).get('requires')) for d in variant.get('dependencies', []) if d.get('group') == 'com.beautysdk'}
            if sdk_deps != {(a, version) for a in allowed_sdk}:
                raise ValueError('beautysdk: incomplete SDK dependencies in consumer variant')
        for dependency in variant.get('dependencies', []):
            required = dependency.get('version', {}).get('requires')
            metadata_dependencies.add((dependency.get('group'), dependency.get('module'), required))
        for entry in variant.get('files', []):
            name = entry.get('name')
            if name not in artifacts or entry.get('url') != name:
                raise ValueError(f'{module}: missing or external variant artifact')
            data = artifacts[name]
            if entry.get('size') != len(data):
                raise ValueError(f'{module}: variant size mismatch')
            if not any(k in entry for k in ('sha256', 'sha512')):
                raise ValueError(f'{module}: variant has no strong digest')
            for algorithm in ('sha256', 'sha512'):
                if algorithm in entry and hashlib.new(algorithm, data).hexdigest() != entry[algorithm]:
                    raise ValueError(f'{module}: variant digest mismatch')
            references.add(name)
    if module == 'beautysdk' and consumer_usages != {'java-api', 'java-runtime'}:
        raise ValueError('beautysdk: missing compile or runtime variant')
    if not {prefix + '.aar', prefix + '-sources.jar'} <= references:
        raise ValueError(f'{module}: incomplete Gradle variants')
    if metadata_dependencies != set(dependencies):
        raise ValueError(f'{module}: POM and Gradle direct dependencies disagree')
    with zipfile.ZipFile(io.BytesIO(artifacts[prefix + '.aar'])) as aar:
        names = aar.namelist()
        if len(names) != len(set(names)) or 'classes.jar' not in names or 'AndroidManifest.xml' not in names:
            raise ValueError(f'{module}: incomplete or duplicate AAR entries')
        with zipfile.ZipFile(io.BytesIO(aar.read('classes.jar'))) as classes:
            if not any(name.endswith('.class') for name in classes.namelist()):
                raise ValueError(f'{module}: empty classes.jar')
    with zipfile.ZipFile(io.BytesIO(artifacts[prefix + '-sources.jar'])) as sources:
        if not any(name.endswith(('.kt', '.java')) for name in sources.namelist()):
            raise ValueError(f'{module}: empty sources artifact')
    return {'module': module, 'version': version, 'directDependencies': dependencies}
