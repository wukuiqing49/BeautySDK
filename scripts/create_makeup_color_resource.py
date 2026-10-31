"""Create a bounded unified makeup color resource using only the Python standard library."""
import argparse
import binascii
import json
import math
from pathlib import Path
import re
import struct
import zipfile
import zlib


def create(region, rgb, strength, name):
    if region not in {'lipstick', 'blusher'} or not re.fullmatch(r'[0-9a-fA-F]{6}', rgb):
        raise ValueError('region must be lipstick/blusher and color must be six hex digits')
    if not math.isfinite(strength) or not 0 <= strength <= 1 or not name.strip() or len(name) > 80 or any(ord(c)<32 for c in name):
        raise ValueError('Invalid strength or name')
    channels=bytes.fromhex(rgb)
    def chunk(kind, data):
        return struct.pack('>I',len(data))+kind+data+struct.pack('>I',binascii.crc32(kind+data)&0xffffffff)
    png=b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',32,32,8,2,0,0,0))+chunk(b'IDAT',zlib.compress((b'\x00'+channels*32)*32))+chunk(b'IEND',b'')
    manifest={'packageVersion':1,'id':region+'_'+rgb.lower(),'revision':1,'category':'MAKEUP','subcategory':region.upper(),
        'slot':'makeup.'+region,'defaultLocale':'en','name':name,'description':'','localizations':{},'preview':'preview.png','effect':'effect.json',
        'requirements':{'features':['makeup.parameters.v1','face.landmarks478','makeup.colors.v1']},'scope':['makeup.'+region]}
    effect={'effectVersion':1,'effectType':'PARAMETERS','payload':{'parameters':{'makeup.'+region:strength},
        'colors':{region:dict(zip(['red','green','blue'],[v/255 for v in channels]))}}}
    return {'manifest.json':json.dumps(manifest,ensure_ascii=False).encode(), 'effect.json':json.dumps(effect).encode(),'preview.png':png}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--region',choices=['lipstick','blusher'],required=True)
    parser.add_argument('--color',required=True,help='Six hexadecimal RGB digits, without #')
    parser.add_argument('--strength',type=float,default=.5)
    parser.add_argument('--name',required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    files=create(args.region,args.color,args.strength,args.name)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(args.output,'w',compression=zipfile.ZIP_DEFLATED) as archive:
        for name,data in files.items():archive.writestr(name,data)
    print(args.output)
