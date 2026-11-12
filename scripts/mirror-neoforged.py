#!/usr/bin/env python3
"""把 maven.neoforged.net 上「1.21.1 那一格」需要的东西镜像到本地目录。

**为什么需要它**：这台机器连不上 `maven.neoforged.net`（代理 502、直连超时），而 NeoForge
的构建缺了它就跑不起来。ModDevGradle 插件本身能从 Gradle Plugin Portal 拿到，缺的只有
`net.neoforged` 这一组制品。

**为什么是「爬」而不是「列清单」**：NeoForge 的 POM 会拖出 `bus` / `accesstransformers` /
`coremods` / `mergetool` / `securejarhandler` / `neoform` 等等同组制品，版本号埋在 POM 里。
手写清单一定会漏，而漏掉的表现是「跑一半再报一个 404」——那比多下一百兆糟得多。所以这里直接
遍历 Nexus 的目录索引，按路径前缀过滤。

**在哪跑**：任何能访问 `maven.neoforged.net` 的机器（开了代理的这台、另一台机器、或者手机热点
都行）。跑完把整个输出目录拷回来即可。

用法：

    python scripts/mirror-neoforged.py --out D:/Kimi/mirror
    python scripts/mirror-neoforged.py --out D:/Kimi/mirror --dry-run   # 只打印会下什么

镜像完成后，`fabric-1.21.1/settings.gradle` 里加一行就能用：

    maven { url = uri('file:///D:/Kimi/mirror') }

镜像的是 `net/neoforged/` 整棵树里跟 1.21.1 有关的部分：`neoforge/21.1.*`、`neoform/1.21.1-*`，
以及所有没有版本前缀的公共库（它们整棵树都很小）。`neoforge/` 的其它版本与 `-sources.jar`
一律跳过——那是几个 G 的量，且与这一格无关。
"""
import argparse
import os
import re
import sys
import urllib.error
import urllib.request

BASE = "https://maven.neoforged.net/releases/net/neoforged/"

# Nexus 3 的目录索引就是一堆 <a href="...">。不要用 HTML 解析库：这里只需要 href。
LINK = re.compile(r'<a\s+href="([^"?][^"]*)"', re.I)

# 这些是「一个版本一个目录」的大件，只取 1.21.1 那条线。
VERSIONED = {
    "neoforge": re.compile(r"^21\.1\."),
    "neoform": re.compile(r"^1\.21\.1-"),
}

# 一律不要：源码包与文档包对构建没用，体积却和主包一样。
SKIP_SUFFIX = ("-sources.jar", "-javadoc.jar", "-sources.jar.sha1", "-javadoc.jar.sha1")

# Nexus 的校验文件很小，全部拿上：Gradle 会校验，缺了会警告。
KEEP_EXTRA = (".jar", ".pom", ".module", ".zip", ".lzma", ".sha1", ".md5", ".sha256", ".sha512")


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "pvpocket-mirror/1"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def listing(url):
    """目录索引里的子项。目录以 `/` 结尾，文件不带。"""
    try:
        html = fetch(url).decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return []
        raise
    names = []
    for href in LINK.findall(html):
        if href.startswith("?") or href.startswith("/") or href.startswith("http"):
            continue
        if href in ("../", ".."):
            continue
        names.append(href)
    return names


def wanted(rel, name):
    """`rel` 是相对 net/neoforged/ 的目录，`name` 是这一层的文件名或子目录名。"""
    top = rel.split("/")[0]
    if top in VERSIONED and "/" not in rel:
        # 版本目录本身：只走 1.21.1 那条线
        return name.endswith("/") and bool(VERSIONED[top].match(name.rstrip("/")))
    if name.endswith("/"):
        return True
    if name.endswith(SKIP_SUFFIX):
        return False
    return name.endswith(KEEP_EXTRA)


def walk(out_root, dry_run):
    queue = [""]
    seen = set()
    files = 0
    total = 0
    while queue:
        rel = queue.pop(0)
        if rel in seen:
            continue
        seen.add(rel)
        url = BASE + rel
        for name in listing(url):
            if not wanted(rel, name):
                continue
            if name.endswith("/"):
                queue.append(rel + name)
                continue
            target = os.path.join(out_root, "net", "neoforged", *rel.split("/"), name)
            files += 1
            if dry_run:
                print("  would fetch %s" % (rel + name))
                continue
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.exists(target) and os.path.getsize(target) > 0:
                continue
            try:
                data = fetch(url + name)
            except urllib.error.HTTPError as error:
                print("  ! %s%s → %s" % (rel, name, error.code), file=sys.stderr)
                continue
            with open(target, "wb") as handle:
                handle.write(data)
            total += len(data)
            if files % 25 == 0:
                print("  …%d 个文件，%.1f MB" % (files, total / 1e6))
    return files, total


def main():
    parser = argparse.ArgumentParser(description="镜像 maven.neoforged.net 的 1.21.1 制品")
    parser.add_argument("--out", required=True, help="输出目录（会当作一个 maven 仓库根）")
    parser.add_argument("--dry-run", action="store_true", help="只打印，不下载")
    options = parser.parse_args()

    out_root = os.path.abspath(options.out)
    print("[mirror] %s → %s" % (BASE, out_root))
    try:
        files, total = walk(out_root, options.dry_run)
    except urllib.error.URLError as error:
        print("[mirror] 连不上 %s：%s" % (BASE, error), file=sys.stderr)
        print("[mirror] 这个脚本要在**能访问该站点**的机器上跑。", file=sys.stderr)
        return 2
    if options.dry_run:
        print("[mirror] 预演结束，共 %d 个文件" % files)
        return 0
    print("[mirror] 完成：%d 个文件，%.1f MB" % (files, total / 1e6))
    print("[mirror] 接下来在 fabric-1.21.1/settings.gradle 里加：")
    print("             maven { url = uri('file:///%s') }" % out_root.replace("\\", "/"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
