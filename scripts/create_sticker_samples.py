"""生成项目原创透明底贴纸；无需下载素材，使用 Pillow 高倍绘制后抗锯齿缩小。"""
from pathlib import Path
import json
import math
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'feature/feature_app/src/main/assets/beauty_stickers'
PREVIEW = ROOT / 'doc/stickers'
SCALE = 4
SIZE = (512, 256)


def polygon(draw, points, fill):
    draw.polygon([(round(x*SCALE), round(y*SCALE)) for x, y in points], fill=fill)


def line(draw, points, fill, width):
    draw.line([(round(x*SCALE), round(y*SCALE)) for x, y in points], fill=fill,
              width=round(width*SCALE), joint='curve')


def ellipse(draw, box, fill=None, outline=None, width=1):
    draw.ellipse(tuple(round(v*SCALE) for v in box), fill=fill, outline=outline, width=width*SCALE)


def heart(cx, cy, radius):
    return [(cx + radius*math.sin(t)**3,
             cy - radius*(13*math.cos(t)-5*math.cos(2*t)-2*math.cos(3*t)-math.cos(4*t))/16)
            for t in [i*math.tau/200 for i in range(201)]]


def make(kind):
    image = Image.new('RGBA', (SIZE[0]*SCALE, SIZE[1]*SCALE))
    d = ImageDraw.Draw(image)
    dark = '#202332'
    if kind == 'round_glasses':
        for cx in (154, 358):
            ellipse(d, (cx-85, 43, cx+85, 213), '#71CDEB30', dark, 13)
            line(d, [(cx-48,82),(cx-66,114)], '#FFFFFFAA', 8)
        line(d, [(237,122),(245,114),(267,114),(275,122)], dark, 11)
        line(d, [(69,116),(20,100)], dark, 13)
        line(d, [(443,116),(492,100)], dark, 13)
    elif kind == 'heart_glasses':
        for cx in (154, 358):
            points = heart(cx,120,96)
            polygon(d,points,'#EE4874')
            polygon(d,heart(cx,120,76),'#E87EB744')
            line(d,[(cx-43,81),(cx-59,102)],'#FFFFFFCC',6)
        line(d,[(240,114),(256,105),(272,114)],'#EE4874',12)
        line(d,[(62,115),(20,100)],'#EE4874',13)
        line(d,[(450,115),(492,100)],'#EE4874',13)
    elif kind == 'curled_moustache':
        # 中央凹口与两侧卷尖构成独立 alpha 轮廓，不能绘制人物或皮肤背景。
        def curve(start, c1, c2, end):
            return [((1-t)**3*start[0]+3*(1-t)**2*t*c1[0]+3*(1-t)*t*t*c2[0]+t**3*end[0],
                     (1-t)**3*start[1]+3*(1-t)**2*t*c1[1]+3*(1-t)*t*t*c2[1]+t**3*end[1])
                    for t in [i/40 for i in range(41)]]
        left = curve((256,108),(194,20),(134,117),(89,126))
        left += curve((89,126),(45,143),(30,91),(63,78))
        left += curve((63,78),(8,66),(14,160),(91,171))
        left += curve((91,171),(156,193),(225,161),(256,108))
        polygon(d,left,'#352632')
        polygon(d,[(512-x,y) for x,y in left],'#352632')
        line(d,[(148,104),(177,86),(203,85)],'#654551',5)
        line(d,[(364,104),(335,86),(309,85)],'#654551',5)
    elif kind == 'blue_top_hat':
        polygon(d,[(129,24),(383,24),(368,210),(144,210)],'#233456')
        polygon(d,[(148,35),(176,35),(188,198),(160,198)],'#3C5278')
        polygon(d,[(143,163),(369,163),(366,202),(146,202)],'#E56482')
        ellipse(d,(49,186,463,244),'#172439')
        line(d,[(83,213),(429,213)],'#455C83',5)
    elif kind == 'gold_crown':
        polygon(d,[(64,64),(151,125),(256,22),(361,125),(448,64),(417,231),(95,231)],'#E9A623')
        polygon(d,[(90,93),(153,145),(256,49),(359,145),(422,93),(402,201),(110,201)],'#FFD865')
        polygon(d,[(95,204),(417,204),(412,239),(100,239)],'#C47C1D')
        for cx in (66,256,446): ellipse(d,(cx-13,13 if cx==256 else 49,cx+13,39 if cx==256 else 75),'#FFE99B')
        for cx in (160,352): ellipse(d,(cx-12,178,cx+12,201),'#E8688A')
        polygon(d,[(256,157),(275,185),(256,213),(237,185)],'#66CBBF')
    elif kind == 'cat_ears':
        for flip in (False,True):
            pts=[(42,224),(55,24),(194,188)]
            inner=[(68,190),(76,65),(161,174)]
            polygon(d,[(512-x,y) if flip else (x,y) for x,y in pts],dark)
            polygon(d,[(512-x,y) if flip else (x,y) for x,y in inner],'#F190AB')
        line(d,[(105,218),(167,193),(256,185),(345,193),(407,218)],dark,17)
    return image.resize(SIZE, Image.Resampling.LANCZOS)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    PREVIEW.mkdir(parents=True, exist_ok=True)
    entries = []
    kinds = ['round_glasses','heart_glasses','curled_moustache','blue_top_hat','gold_crown','cat_ears']
    for kind in kinds:
        image = make(kind)
        image.save(DEST / f'{kind}.png', optimize=True)
        is_mouth = kind == 'curled_moustache'
        is_head = kind in kinds[3:]
        entries.append(dict(id=kind, file=f'{kind}.png', width=512, height=256,
                            anchor='UPPER_LIP' if is_mouth else 'EYE_MIDPOINT',
                            pivot=[.5,1.45 if is_head else (.40 if is_mouth else .5)],
                            widthInAnchorSpans=2.5 if not is_mouth else 1.7,
                            span='MOUTH_CORNERS' if is_mouth else 'EYES'))
    (DEST/'manifest.json').write_text(json.dumps(dict(version=1, stickers=entries),indent=2)+'\n',encoding='utf-8')
    # 棋盘仅用于预览，实际 PNG 只包含透明背景和挂件本身。
    sheet = Image.new('RGB',(1104,1008),'#EDF0F5')
    draw = ImageDraw.Draw(sheet)
    try:
        font = ImageFont.truetype('C:/Windows/Fonts/arial.ttf',22)
    except OSError:
        font = ImageFont.load_default(size=22)
    for i, kind in enumerate(kinds):
        x=24+(i%2)*552; y=24+(i//2)*328
        for yy in range(0,256,16):
            for xx in range(0,512,16):
                draw.rectangle((x+xx,y+yy,x+xx+15,y+yy+15),fill='#FFFFFF' if (xx//16+yy//16)%2 else '#DCE1E9')
        sheet.paste(make(kind),(x,y),make(kind))
        draw.text((x+6,y+270),kind,font=font,fill='#202332')
    sheet.save(PREVIEW/'sample-stickers.jpg',quality=92)
    for kind in kinds:
        image=Image.open(DEST/f'{kind}.png')
        assert image.mode=='RGBA' and image.getchannel('A').getextrema()==(0,255)
    print('Generated and alpha-validated 6 PNG stickers, manifest and preview.')


if __name__ == '__main__':
    main()
