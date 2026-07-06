#!/bin/bash
set -e

SOURCE="icon.2048.png"
RES="app/src/main/res"

if [ ! -f "$SOURCE" ]; then
    echo "❌ 找不到 $SOURCE，请先把 2048px 图标放到项目根目录"
    exit 1
fi

echo "🎨 从 $SOURCE 生成 Android 图标..."

# ─── 清理旧的 webp 图标 ───
echo "  清理旧图标..."
find "$RES" -name "ic_launcher.webp" -delete 2>/dev/null || true
find "$RES" -name "ic_launcher_round.webp" -delete 2>/dev/null || true

# ─── Legacy 图标 (Android 7.x 及以下) ───
resize_legacy() {
    local dir=$1 size=$2
    mkdir -p "$RES/$dir"
    cp "$SOURCE" "$RES/$dir/ic_launcher.png"
    sips -Z "$size" "$RES/$dir/ic_launcher.png" > /dev/null 2>&1
    cp "$SOURCE" "$RES/$dir/ic_launcher_round.png"
    sips -Z "$size" "$RES/$dir/ic_launcher_round.png" > /dev/null 2>&1
    echo "  ✓  $dir → ${size}×${size}"
}

resize_legacy "mipmap-mdpi"    48
resize_legacy "mipmap-hdpi"    72
resize_legacy "mipmap-xhdpi"   96
resize_legacy "mipmap-xxhdpi" 144
resize_legacy "mipmap-xxxhdpi" 192

# ─── Adaptive 图标前景 (Android 8+, 108dp 画布) ───
resize_fg() {
    local dir=$1 size=$2
    mkdir -p "$RES/$dir"
    cp "$SOURCE" "$RES/$dir/ic_launcher_foreground.png"
    sips -Z "$size" "$RES/$dir/ic_launcher_foreground.png" > /dev/null 2>&1
    echo "  ✓  $dir foreground → ${size}×${size}"
}

resize_fg "mipmap-mdpi"    108
resize_fg "mipmap-hdpi"    162
resize_fg "mipmap-xhdpi"   216
resize_fg "mipmap-xxhdpi"  324
resize_fg "mipmap-xxxhdpi" 432

# ─── Play Store 图标 (512×512) ───
cp "$SOURCE" "$RES/mipmap-xxxhdpi/ic_launcher_playstore.png"
sips -Z 512 "$RES/mipmap-xxxhdpi/ic_launcher_playstore.png" > /dev/null 2>&1
echo "  ✓  Play Store → 512×512"

echo "✅ 图标生成完成！"
