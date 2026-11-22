#!/usr/bin/env bash
# 生成/更新国内备用更新源清单：把 dist 里的 APK + docs/releases/*.md 变成 manifest.json。
#
# 用法：
#   bash scripts/publish_update_mirror.sh 0.8.0                      # 只生成
#   bash scripts/publish_update_mirror.sh 0.8.0 --deploy             # 生成并上传
#   MIRROR_HOST=root@8.133.167.195 bash scripts/publish_update_mirror.sh 0.8.0 dist/x.apk --deploy
#
# 关键约束（2026-11 真实事故）：manifest 里的 apkUrl 必须和 version 对得上。
# 上一版把 0.7.0 指向了 0.6.1 的 apk，客户端因大小/哈希不符全部拒绝。
# 这里按“目录里实际存在的 APK”重建清单，大小和 SHA-256 都现算，不手写。
set -euo pipefail
VERSION=${1:?usage: publish_update_mirror.sh <version> [apk] [--deploy]}
APK=${2:-dist/Rikkahub-M-${VERSION}.apk}
if [[ "${2:-}" == "--deploy" ]]; then APK="dist/Rikkahub-M-${VERSION}.apk"; fi
[[ -f "$APK" ]] || { echo "ERROR: $APK 不存在"; exit 1; }

OUT_DIR=tools/update-mirror/out
mkdir -p "$OUT_DIR"
cp "$APK" "$OUT_DIR/Rikkahub-M-${VERSION}.apk"

# 把上一个 manifest 里已有的用户版说明保留下来，避免重建时丢失历史版本的更新日志
if [[ -f "$OUT_DIR/manifest.json" ]]; then
  cp "$OUT_DIR/manifest.json" "$OUT_DIR/manifest.previous.json"
fi

python3 - "$VERSION" "$OUT_DIR" <<'PY'
import datetime, hashlib, json, pathlib, re, sys

version, out_dir = sys.argv[1], pathlib.Path(sys.argv[2])
out = out_dir / "manifest.json"
previous = out_dir / "manifest.previous.json"
old = {}
if previous.exists():
    try:
        old = {r.get("version"): r for r in json.loads(previous.read_text(encoding="utf-8")).get("releases", [])}
    except Exception:
        old = {}

releases = []
for apk in sorted(out_dir.glob("Rikkahub-M-*.apk")):
    v = apk.stem.removeprefix("Rikkahub-M-")
    notes = pathlib.Path(f"docs/releases/{v}.md")
    body = notes.read_text(encoding="utf-8") if notes.exists() else old.get(v, {}).get("body", "")
    prev = old.get(v, {})
    releases.append({
        "version": v,
        "tag": f"v{v}",
        "title": prev.get("title") or f"Rikkahub-M {v}",
        "summary": prev.get("summary", ""),
        "body": body,
        "publishedAt": prev.get("publishedAt") or datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "prerelease": "-" in v,
        "source": "mirror",
        "apkUrl": f"https://8.133.167.195/updates/{apk.name}",
        "apkSize": apk.stat().st_size,
        "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
        "pageUrl": f"https://github.com/cs9527h/Rikkahub-M/releases/tag/v{v}",
    })

# 自检：清单里每个条目都必须自带同名 APK，且大小与哈希来自这个文件
for release in releases:
    name = release["apkUrl"].rsplit("/", 1)[-1]
    expected = f"Rikkahub-M-{release['version']}.apk"
    if name != expected:
        raise SystemExit(f"manifest 自检失败：{release['version']} 指向 {name}")
    if not (out_dir / name).is_file():
        raise SystemExit(f"manifest 自检失败：缺少 {name}")

out.write_text(json.dumps({
    "format": 1,
    "updatedAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    "releases": releases,
}, ensure_ascii=False, indent=2), encoding="utf-8")
for release in releases:
    print(f"{release['version']:>8}  {release['apkSize']:>10}  {release['sha256'][:16]}  {release['apkUrl'].rsplit('/', 1)[-1]}")
print("manifest written:", out)
PY
rm -f "$OUT_DIR/manifest.previous.json"

cat > "$OUT_DIR/nginx-updates.conf" <<'NGINX'
# 追加到现有 server{} 块；不影响备份网关的 location /。
location /updates/ {
    alias /var/www/updates/;
    types { application/vnd.android.package-archive apk; }
    add_header Cache-Control "public, max-age=300";
    autoindex off;
}
NGINX

if [[ "${3:-}" == "--deploy" || "${2:-}" == "--deploy" ]]; then
  HOST=${MIRROR_HOST:-root@8.133.167.195}
  ssh "$HOST" 'mkdir -p /var/www/updates'
  scp "$OUT_DIR"/Rikkahub-M-*.apk "$OUT_DIR/manifest.json" "$HOST:/var/www/updates/"
  ssh "$HOST" "python3 - <<'PY'
import json, urllib.request
data = json.load(open('/var/www/updates/manifest.json'))
latest = max(data['releases'], key=lambda r: r['version'])
print('deployed latest:', latest['version'], latest['apkSize'], latest['sha256'][:16])
PY"
  echo "已上传到 $HOST:/var/www/updates；确认 nginx 已包含 nginx-updates.conf 里的 location 后 reload。"
else
  echo "生成完毕，未部署。部署：bash scripts/publish_update_mirror.sh $VERSION dist/Rikkahub-M-$VERSION.apk --deploy"
fi
