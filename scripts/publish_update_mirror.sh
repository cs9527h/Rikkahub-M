#!/usr/bin/env bash
# 生成国内备用更新源清单：把 dist 里的 APK + docs/releases/*.md 变成 manifest.json。
# 用法：bash scripts/publish_update_mirror.sh 0.4.3 [--deploy]
set -euo pipefail
VERSION=${1:?usage: publish_update_mirror.sh <version> [--deploy]}
APK=${2:-dist/Rikkahub-M-${VERSION}.apk}
[[ -f "$APK" ]] || { echo "ERROR: $APK 不存在"; exit 1; }
NOTES="docs/releases/${VERSION}.md"
[[ -f "$NOTES" ]] || { echo "ERROR: $NOTES 不存在"; exit 1; }

OUT_DIR=tools/update-mirror/out
mkdir -p "$OUT_DIR"
cp "$APK" "$OUT_DIR/Rikkahub-M-${VERSION}.apk"
SHA=$(sha256sum "$OUT_DIR/Rikkahub-M-${VERSION}.apk" | awk '{print $1}')
SIZE=$(stat -c%s "$OUT_DIR/Rikkahub-M-${VERSION}.apk")

python3 - "$VERSION" "$SHA" "$SIZE" "$NOTES" "$OUT_DIR/manifest.json" <<'PY'
import json, pathlib, sys
version, sha, size, notes, out = sys.argv[1:6]
pathlib.Path(out).write_text(json.dumps({
    "format": 1,
    "updatedAt": "auto",
    "releases": [{
        "version": version,
        "tag": f"v{version}",
        "title": f"Rikkahub-M {version}",
        "body": pathlib.Path(notes).read_text(),
        "publishedAt": __import__("datetime").datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ"),
        "prerelease": "-" in version,
        "apkUrl": f"https://8.133.167.195/updates/Rikkahub-M-{version}.apk",
        "sha256": sha,
        "apkSize": int(size),
        "pageUrl": f"https://github.com/cs9527h/Rikkahub-M/releases/tag/v{version}",
        "source": "mirror",
    }],
}, ensure_ascii=False, indent=2))
print("manifest written:", out, "sha256:", sha)
PY

cat > "$OUT_DIR/nginx-updates.conf" <<'EOF'
# 追加到现有 server{} 块；不影响备份网关的 location /。
location /updates/ {
    alias /var/www/updates/;
    types { application/vnd.android.package-archive apk; }
    add_header Cache-Control "public, max-age=300";
    autoindex off;
}
EOF

if [[ "${3:-}" == "--deploy" ]]; then
  HOST=${MIRROR_HOST:-root@8.133.167.195}
  ssh "$HOST" 'mkdir -p /var/www/updates'
  scp "$OUT_DIR/Rikkahub-M-${VERSION}.apk" "$OUT_DIR/manifest.json" "$HOST:/var/www/updates/"
  echo "已上传到 $HOST:/var/www/updates；确认 nginx 已包含 nginx-updates.conf 里的 location 后 reload。"
else
  echo "生成完毕，未部署。部署需服务器 SSH 授权：MIRROR_HOST=root@8.133.167.195 bash scripts/publish_update_mirror.sh $VERSION dist/Rikkahub-M-$VERSION.apk --deploy"
fi
