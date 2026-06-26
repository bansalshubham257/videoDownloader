"""
Generate the OG social preview image for QuickSaveVideos.
Run this once locally or at app startup to produce static/og-image.png
"""
import os
from pathlib import Path

def generate_og_image(output_path: str = None):
    try:
        from PIL import Image, ImageDraw, ImageFont
    except ImportError:
        print("⚠️ Pillow not installed – skipping OG image generation")
        return False

    W, H = 1200, 630

    # ── Background gradient (indigo → violet) ──────────────────────────
    img  = Image.new('RGB', (W, H), '#4f46e5')
    draw = ImageDraw.Draw(img)

    # Paint a simple gradient by drawing horizontal bands
    for y in range(H):
        t   = y / H
        r   = int(79  + t * (124 - 79))
        g   = int(70  + t * (58  - 70))
        b   = int(229 + t * (213 - 229))
        draw.line([(0, y), (W, y)], fill=(r, g, b))

    # ── Top decorative circles ──────────────────────────────────────────
    draw.ellipse([(-80, -80), (220, 220)],   fill=(255, 255, 255, 20))
    draw.ellipse([(980, -60), (1300, 260)],  fill=(255, 255, 255, 15))
    draw.ellipse([(-40, 430), (200, 680)],   fill=(255, 255, 255, 12))
    draw.ellipse([(1050, 400), (1300, 700)], fill=(255, 255, 255, 10))

    # ── White card in the centre ────────────────────────────────────────
    cx, cy     = W // 2, H // 2
    card_w, card_h = 960, 440
    cx0, cy0   = cx - card_w // 2, cy - card_h // 2
    cx1, cy1   = cx + card_w // 2, cy + card_h // 2

    # Draw rounded-rect card (manual approach for Pillow compat)
    radius = 24
    draw.rounded_rectangle([cx0, cy0, cx1, cy1], radius=radius,
                            fill=(255, 255, 255), outline=(230, 230, 250), width=2)

    # ── Icon placeholder ────────────────────────────────────────────────
    icon_y = cy0 + 52
    draw.ellipse([cx - 36, icon_y, cx + 36, icon_y + 72],
                 fill='#4f46e5')

    # Draw a simple download arrow in the circle
    arrow_cx, arrow_cy = cx, icon_y + 36
    draw.rectangle([arrow_cx - 6, arrow_cy - 16, arrow_cx + 6, arrow_cy + 4],
                   fill='white')
    draw.polygon([
        (arrow_cx - 16, arrow_cy + 4),
        (arrow_cx + 16, arrow_cy + 4),
        (arrow_cx,      arrow_cy + 22),
    ], fill='white')

    # ── Try to load fonts, fall back to default ─────────────────────────
    try:
        font_large  = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc', 52)
        font_medium = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc', 28)
        font_small  = ImageFont.truetype('/System/Library/Fonts/Helvetica.ttc', 22)
    except Exception:
        try:
            font_large  = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 52)
            font_medium = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 28)
            font_small  = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 22)
        except Exception:
            font_large  = ImageFont.load_default()
            font_medium = font_large
            font_small  = font_large

    text_color    = '#0f172a'
    muted_color   = '#64748b'
    accent_color  = '#4f46e5'

    # ── Title ───────────────────────────────────────────────────────────
    title = 'QuickSaveVideos'
    bbox  = draw.textbbox((0, 0), title, font=font_large)
    tw    = bbox[2] - bbox[0]
    draw.text((cx - tw // 2, cy0 + 136), title, fill=text_color, font=font_large)

    # ── Subtitle ─────────────────────────────────────────────────────────
    subtitle = 'Free Video Downloader — Instagram, YouTube, TikTok, Twitter & More'
    bbox2    = draw.textbbox((0, 0), subtitle, font=font_medium)
    sw       = bbox2[2] - bbox2[0]
    draw.text((cx - sw // 2, cy0 + 206), subtitle, fill=muted_color, font=font_medium)

    # ── Divider ───────────────────────────────────────────────────────────
    div_y = cy0 + 258
    draw.rectangle([cx0 + 80, div_y, cx1 - 80, div_y + 1], fill='#e2e8f0')

    # ── Platform pills ────────────────────────────────────────────────────
    platforms = ['Instagram', 'YouTube', 'TikTok', 'Twitter/X', 'Facebook', 'Pinterest']
    colors    = ['#e1306c', '#ff0000', '#010101', '#1a8cd8', '#1877f2', '#e60023']
    pill_h    = 40
    pill_y    = cy0 + 280
    gap       = 14

    # Measure total width first
    pill_widths = []
    for p in platforms:
        bx = draw.textbbox((0, 0), p, font=font_small)
        pill_widths.append(bx[2] - bx[0] + 32)

    total_w = sum(pill_widths) + gap * (len(platforms) - 1)
    px      = cx - total_w // 2

    for i, (p, col, pw) in enumerate(zip(platforms, colors, pill_widths)):
        # Pill background
        draw.rounded_rectangle([px, pill_y, px + pw, pill_y + pill_h],
                                radius=pill_h // 2, fill=col + '20', outline=col + '50', width=1)
        # Text
        bx   = draw.textbbox((0, 0), p, font=font_small)
        tw   = bx[2] - bx[0]
        tx   = px + (pw - tw) // 2
        ty   = pill_y + (pill_h - (bx[3] - bx[1])) // 2
        draw.text((tx, ty), p, fill=col, font=font_small)
        px  += pw + gap

    # ── Bottom line ───────────────────────────────────────────────────────
    footer_txt = 'quicksavevideos.com  •  No login required  •  Free & fast'
    bft        = draw.textbbox((0, 0), footer_txt, font=font_small)
    fw         = bft[2] - bft[0]
    draw.text((cx - fw // 2, cy0 + 352), footer_txt, fill=accent_color, font=font_small)

    # ── Save ──────────────────────────────────────────────────────────────
    if output_path is None:
        output_path = os.path.join(
            os.path.dirname(os.path.abspath(__file__)),
            'static', 'og-image.png'
        )

    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    img.save(output_path, 'PNG', optimize=True)
    size_kb = os.path.getsize(output_path) // 1024
    print(f"✅ OG image saved: {output_path} ({size_kb} KB)")
    return True


if __name__ == '__main__':
    generate_og_image()

