#!/usr/bin/env python3
"""从 class/dex 文件提取中文字符串(用于恢复被编码事故破坏的 Kotlin 源文件)。"""
import glob
import re
import sys


def extract_utf8_strings(data: bytes):
    """扫描字节流,提取连续的合法 UTF-8 多字节串(长度>=2,含 CJK)。"""
    out = []
    i = 0
    n = len(data)
    while i < n:
        b = data[i]
        if b < 0x80:
            i += 1
            continue
        # 尝试解码以 i 开头的 UTF-8 序列
        try:
            # 从 i 开始读最长合法串
            j = i
            buf = bytearray()
            while j < n:
                nb = data[j]
                if nb < 0x80:
                    break
                buf.append(nb)
                j += 1
            s = buf.decode("utf-8", errors="strict")
            if any('\u4e00' <= c <= '\u9fff' for c in s) and len(s) >= 2:
                out.append(s)
            i = j
        except UnicodeDecodeError:
            i += 1
    return out


def main():
    paths = sys.argv[1:]
    found = set()
    for p in paths:
        for f in glob.glob(p):
            with open(f, "rb") as fh:
                data = fh.read()
            for s in extract_utf8_strings(data):
                found.add(s)
    for s in sorted(found):
        print(s)


if __name__ == "__main__":
    main()
