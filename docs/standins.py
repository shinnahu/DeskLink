"""Stand-in pictures of a PC for the README's phone screenshots (nothing from a real screen): a generic desktop on
one screen, two screens joined the way DeskLink shows all of them at once, and a game scene."""
import math
import os
import sys

from PIL import Image, ImageDraw, ImageFilter, ImageFont

out = sys.argv[1]
os.makedirs(out, exist_ok=True)


def font(size, bold=False):
    return ImageFont.truetype(r'C:\Windows\Fonts\segoeuib.ttf' if bold else r'C:\Windows\Fonts\segoeui.ttf', size)


def gradient(w, h, top, bottom):
    img = Image.new('RGB', (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / (h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.convert('RGBA')


def wallpaper(w, h, top, bottom, blobs):
    img = gradient(w, h, top, bottom)
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    g = ImageDraw.Draw(glow)
    for cx, cy, r, col in blobs:
        g.ellipse([cx - r, cy - r, cx + r, cy + r], fill=col)
    return Image.alpha_composite(img, glow.filter(ImageFilter.GaussianBlur(140)))


def layer(img, draw):
    """Semi-transparent shapes: drawn on their own layer and blended in (drawing them straight on replaces pixels)."""
    ov = Image.new('RGBA', img.size, (0, 0, 0, 0))
    draw(ImageDraw.Draw(ov))
    img.alpha_composite(ov)


def shadow(img, box, r=12):
    s = Image.new('RGBA', img.size, (0, 0, 0, 0))
    ImageDraw.Draw(s).rounded_rectangle([box[0] + 4, box[1] + 16, box[2] + 4, box[3] + 16], r, fill=(0, 0, 0, 120))
    img.alpha_composite(s.filter(ImageFilter.GaussianBlur(22)))


def window(img, x, y, w, h, title, light=False):
    shadow(img, (x, y, x + w, y + h))
    d = ImageDraw.Draw(img)
    frame, text = ((243, 243, 243), (32, 32, 32)) if light else ((32, 33, 37), (232, 234, 238))
    d.rounded_rectangle([x, y, x + w, y + h], 10, fill=frame + (255,), outline=(70, 72, 78, 255) if not light else (200, 200, 205, 255))
    d.text((x + 18, y + 9), title, font=font(17), fill=text)
    cy = y + 21  # minimize, maximize, close
    for i, cx in enumerate((x + w - 128, x + w - 82, x + w - 36)):
        if i == 0:
            d.line([(cx - 7, cy), (cx + 7, cy)], fill=text, width=2)
        elif i == 1:
            d.rectangle([cx - 7, cy - 7, cx + 7, cy + 7], outline=text, width=2)
        else:
            d.line([(cx - 7, cy - 7), (cx + 7, cy + 7)], fill=text, width=2)
            d.line([(cx - 7, cy + 7), (cx + 7, cy - 7)], fill=text, width=2)
    return d


def folder(d, x, y, s, col=(247, 196, 70)):
    d.rounded_rectangle([x, y + s * 0.12, x + s * 0.45, y + s * 0.3], 4, fill=tuple(int(c * 0.85) for c in col))
    d.rounded_rectangle([x, y + s * 0.2, x + s, y + s * 0.8], 6, fill=col)


def documents(img, x, y, w, h):
    d = window(img, x, y, w, h, 'Documents')
    d.rectangle([x + 1, y + 44, x + 210, y + h - 1], fill=(26, 27, 30))
    side = ['Home', 'Desktop', 'Downloads', 'Documents', 'Pictures', 'Music']
    cols = [(120, 170, 255), (90, 200, 160), (110, 190, 255), (247, 196, 70), (240, 120, 160), (190, 140, 255)]
    for i, (n, c) in enumerate(zip(side, cols)):
        ry = y + 66 + i * 44
        if n == 'Documents':
            d.rounded_rectangle([x + 10, ry - 8, x + 200, ry + 30], 6, fill=(48, 50, 56))
        d.rounded_rectangle([x + 22, ry, x + 42, ry + 20], 5, fill=c)
        d.text((x + 56, ry - 3), n, font=font(17), fill=(220, 222, 226))
    d.text((x + 240, y + 62), 'Documents', font=font(26, True), fill=(240, 242, 246))
    names = ['Projects', 'Invoices', 'Photos 2026', 'Recipes', 'Taxes', 'Travel', 'School', 'Music sheets']
    for i, n in enumerate(names):
        cx = x + 250 + (i % 4) * 150
        cy = y + 130 + (i // 4) * 150
        folder(d, cx, cy, 92)
        tw = d.textlength(n, font=font(16))
        d.text((cx + 46 - tw / 2, cy + 86), n, font=font(16), fill=(214, 216, 220))


def notes(img, x, y, w, h):
    d = window(img, x, y, w, h, 'Notes', light=True)
    d.rectangle([x + 1, y + 44, x + w - 1, y + h - 1], fill=(252, 252, 252))
    lines = [('Weekend', True), ('', False), ('•  Fix the bike', False), ('•  Groceries: rice, eggs, coffee', False),
             ('•  Call grandma', False), ('•  Back up the photos', False), ('•  Movie night with friends', False)]
    for i, (t, b) in enumerate(lines):
        d.text((x + 28, y + 66 + i * 36), t, font=font(26 if b else 20, b), fill=(40, 40, 44))


def photos(img, x, y, w, h):
    d = window(img, x, y, w, h, 'Photos')
    d.text((x + 24, y + 60), 'Summer', font=font(26, True), fill=(240, 242, 246))
    palette = [((255, 183, 94), (255, 94, 98)), ((86, 204, 242), (47, 128, 237)), ((168, 224, 99), (86, 171, 47)),
               ((247, 151, 30), (255, 210, 0)), ((238, 9, 121), (255, 106, 0)), ((0, 198, 255), (0, 114, 255)),
               ((142, 45, 226), (74, 0, 224)), ((17, 153, 142), (56, 239, 125)), ((252, 70, 107), (63, 94, 251))]
    tw, th = (w - 48 - 2 * 14) // 3, 150
    for i, (a, b) in enumerate(palette):
        tx, ty = x + 24 + (i % 3) * (tw + 14), y + 110 + (i // 3) * (th + 14)
        tile = gradient(tw, th, a, b)
        m = Image.new('L', (tw, th), 0)
        ImageDraw.Draw(m).rounded_rectangle([0, 0, tw - 1, th - 1], 10, fill=255)
        img.paste(tile, (tx, ty), m)

        def scene(dd, tx=tx, ty=ty):  # a sun and two hills on each picture
            dd.ellipse([tx + tw * 0.62, ty + th * 0.18, tx + tw * 0.62 + 30, ty + th * 0.18 + 30], fill=(255, 255, 255, 190))
            dd.polygon([(tx + 2, ty + th - 2), (tx + tw * 0.35, ty + th * 0.5), (tx + tw * 0.72, ty + th - 2)], fill=(0, 0, 0, 60))
            dd.polygon([(tx + tw * 0.4, ty + th - 2), (tx + tw * 0.72, ty + th * 0.62), (tx + tw - 2, ty + th - 2)], fill=(0, 0, 0, 40))
        layer(img, scene)


def taskbar(img):
    w, h = img.size
    d = ImageDraw.Draw(img)
    d.rectangle([0, h - 48, w, h], fill=(24, 25, 30, 255))
    icons = [(0, 120, 212), (247, 196, 70), (66, 133, 244), (240, 240, 240), (160, 110, 255), (60, 200, 140)]
    x0 = w // 2 - len(icons) * 26
    for i, c in enumerate(icons):
        x = x0 + i * 52
        if i == 0:  # start
            for k in range(4):
                d.rectangle([x + 9 + (k % 2) * 10, h - 37 + (k // 2) * 10, x + 17 + (k % 2) * 10, h - 29 + (k // 2) * 10], fill=c)
        else:
            d.rounded_rectangle([x + 6, h - 40, x + 34, h - 12], 7, fill=c)
    d.text((w - 74, h - 38), '10:24', font=font(17), fill=(236, 238, 242))


def desktop_a():
    img = wallpaper(1920, 1080, (24, 44, 112), (88, 38, 150),
                    [(400, 300, 420, (70, 150, 255, 140)), (1500, 800, 500, (230, 90, 200, 120)), (1200, 200, 300, (90, 220, 255, 90))])
    documents(img, 150, 120, 860, 520)
    notes(img, 1080, 330, 620, 420)
    taskbar(img)
    return img


def desktop_b():
    img = wallpaper(1920, 1080, (14, 70, 90), (30, 40, 100),
                    [(500, 700, 460, (40, 210, 180, 130)), (1500, 250, 420, (90, 120, 255, 120))])
    photos(img, 260, 140, 760, 680)
    taskbar(img)
    return img


def game():
    w, h = 1920, 1080
    img = gradient(w, h, (255, 176, 102), (118, 62, 168))
    d = ImageDraw.Draw(img)
    glow = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([1100, 250, 1500, 650], fill=(255, 220, 150, 200))
    img.alpha_composite(glow.filter(ImageFilter.GaussianBlur(60)))
    d = ImageDraw.Draw(img)
    d.ellipse([1220, 370, 1380, 530], fill=(255, 236, 180))

    def ridge(base, amp, col, k):
        pts = [(0, h)]
        for x in range(0, w + 40, 40):
            y = base - amp * (0.55 * math.sin(x / 260 + k) + 0.3 * math.sin(x / 97 + 2 * k) + 0.15 * math.sin(x / 41 + k))
            pts.append((x, y))
        pts.append((w, h))
        d.polygon(pts, fill=col)

    ridge(640, 120, (150, 80, 160), 0.4)
    ridge(720, 90, (100, 54, 130), 1.7)
    ridge(800, 60, (62, 36, 92), 3.1)
    d.rectangle([0, 860, w, h], fill=(38, 24, 58))
    # the player: a little hooded figure with a lantern
    cx, gy = 960, 900
    d.ellipse([cx - 60, gy - 14, cx + 60, gy + 10], fill=(20, 12, 32))
    d.polygon([(cx - 34, gy), (cx + 34, gy), (cx + 18, gy - 120), (cx - 18, gy - 120)], fill=(30, 20, 46))
    d.ellipse([cx - 26, gy - 160, cx + 26, gy - 108], fill=(30, 20, 46))
    d.ellipse([cx + 40, gy - 92, cx + 64, gy - 68], fill=(255, 214, 120))
    lamp = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    ImageDraw.Draw(lamp).ellipse([cx - 20, gy - 160, cx + 124, gy], fill=(255, 200, 110, 90))
    img.alpha_composite(lamp.filter(ImageFilter.GaussianBlur(30)))
    # HUD: health and energy top left, a minimap top right

    def hud(d):
        d.rounded_rectangle([60, 56, 460, 84], 14, fill=(0, 0, 0, 110))
        d.rounded_rectangle([64, 60, 380, 80], 10, fill=(232, 70, 90, 255))
        d.rounded_rectangle([60, 96, 360, 116], 10, fill=(0, 0, 0, 110))
        d.rounded_rectangle([64, 100, 300, 112], 6, fill=(90, 190, 255, 255))
        d.ellipse([1700, 50, 1860, 210], fill=(20, 10, 40, 110), outline=(255, 255, 255, 150), width=3)
        d.polygon([(1780, 112), (1772, 140), (1788, 140)], fill=(255, 255, 255, 230))
    layer(img, hud)
    return img


a, b = desktop_a(), desktop_b()
a.convert('RGB').save(os.path.join(out, 'desk-one.png'))
both = Image.new('RGB', (3840, 1200), (0, 0, 0))  # as DeskLink joins them: black where no screen is
both.paste(a.convert('RGB'), (0, 0))
both.paste(b.convert('RGB'), (1920, 120))
both.save(os.path.join(out, 'desk-all.png'))
game().convert('RGB').save(os.path.join(out, 'game.png'))
print('ok')
