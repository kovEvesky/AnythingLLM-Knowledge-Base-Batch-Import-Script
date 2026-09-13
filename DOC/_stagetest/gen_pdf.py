# -*- coding: utf-8 -*-
"""生成一个最小但合法的单页 PDF,模拟用户分享的文档文件。"""
out = r"D:\WSL\object\004AnythingLLM-Android\DOC\_stagetest\用户流程测试文档.pdf"
text = b"(v1.3 user-flow test PDF 20260911)"

objs = []
objs.append(b"<< /Type /Catalog /Pages 2 0 R >>")
objs.append(b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
objs.append(b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>")
stream = b"BT /F1 20 Tf 72 720 Td (" + text + b") Tj ET"
objs.append(b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream")
objs.append(b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")

pdf = bytearray(b"%PDF-1.4\n")
offsets = []
for i, o in enumerate(objs):
    offsets.append(len(pdf))
    pdf += str(i + 1).encode() + b" 0 obj\n" + o + b"\nendobj\n"
xref_pos = len(pdf)
pdf += b"xref\n0 " + str(len(objs) + 1).encode() + b"\n"
pdf += b"0000000000 65535 f \n"
for off in offsets:
    pdf += ("%010d 00000 n \n" % off).encode()
pdf += b"trailer\n<< /Size " + str(len(objs) + 1).encode() + b" /Root 1 0 R >>\nstartxref\n" + str(xref_pos).encode() + b"\n%%EOF"

with open(out, "wb") as f:
    f.write(bytes(pdf))
print("written:", out, len(pdf), "bytes")
