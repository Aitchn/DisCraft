#!/usr/bin/env python3
"""Original DisCraft geometric/pixel artwork. Reproduce with Python 3 + Pillow; no external assets/fonts."""
from pathlib import Path
from PIL import Image, ImageDraw

OUT=Path(__file__).resolve().parents[1]/'forge/src/main/resources/discraft-banner.png'
S=2
im=Image.new('RGB',(1024*S,256*S),'#101c2e');d=ImageDraw.Draw(im)
def box(rect,fill):d.rectangle(tuple(int(v*S) for v in rect),fill=fill)
def poly(points,fill):d.polygon([(x*S,y*S) for x,y in points],fill=fill)
# Deliberately drawn blocks connected by a stepped message ribbon: our own geometry, no platform logo.
for x in range(0,1024,32):box((x,238,x+15,255),'#15263d')
poly([(36,82),(100,48),(164,82),(100,117)],'#69dfc6')
poly([(36,82),(100,117),(100,191),(36,155)],'#247c91')
poly([(100,117),(164,82),(164,155),(100,191)],'#3eabb2')
box((116,95,211,120),'#bca7ff');box((187,95,211,162),'#bca7ff')
box((187,138,268,162),'#bca7ff');poly([(245,162),(268,162),(245,183)],'#bca7ff')
for x in [140,166,193]:box((x,103,x+8,111),'#101c2e')
# Hand-authored 5x7 glyphs; artwork source is fully reproducible and uses no third-party typeface.
GLYPHS={
'D':['11110','10001','10001','10001','10001','10001','11110'],
'i':['00100','00000','01100','00100','00100','00100','01110'],
's':['00000','01111','10000','01110','00001','10001','01110'],
'C':['01111','10000','10000','10000','10000','10000','01111'],
'r':['00000','10110','11001','10000','10000','10000','10000'],
'a':['00000','01110','00001','01111','10001','10011','01101'],
'f':['00110','01001','01000','11100','01000','01000','01000'],
't':['01000','01000','11110','01000','01000','01001','00110'],
'A':['01110','10001','10001','11111','10001','10001','10001'],
'H':['10001','10001','10001','11111','10001','10001','10001'],
'T':['11111','00100','00100','00100','00100','00100','00100'],
'M':['10001','11011','10101','10101','10001','10001','10001'],
'E':['11111','10000','10000','11110','10000','10000','11111'],
'I':['01110','00100','00100','00100','00100','00100','01110'],
'N':['10001','11001','11001','10101','10011','10011','10001'],
'R':['11110','10001','10001','11110','10100','10010','10001'],
'F':['11111','10000','10000','11110','10000','10000','10000'],
'/':['00001','00001','00010','00100','01000','10000','10000'],
' ':['00000']*7}
def text(value,x,y,pixel,color):
 for ch in value:
  for row,bits in enumerate(GLYPHS[ch]):
   for col,bit in enumerate(bits):
    if bit=='1':box((x+col*pixel,y+row*pixel,x+(col+1)*pixel-1,y+(row+1)*pixel-1),color)
  x+=6*pixel
text('DisCraft',318,58,12,'#edf6ff')
box((318,160,888,163),'#4b728b')
text('CHAT / MEDIA / MINECRAFT',320,184,4,'#89d9df')
im=im.resize((1024,256),Image.Resampling.LANCZOS);OUT.parent.mkdir(parents=True,exist_ok=True);im.save(OUT,optimize=True)
print(OUT.relative_to(OUT.parents[4]))
