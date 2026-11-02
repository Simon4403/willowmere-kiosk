"""Independent PWG Raster (PWG 5102.4) decoder, written from the spec, to check the kiosk's encoder."""
import struct, sys
def decode(data):
    assert data[:4] == b'RaS2', 'sync word'
    pos, pages = 4, []
    while pos < len(data):
        h = data[pos:pos+1796]; pos += 1796
        g = lambda off: struct.unpack('>I', h[off:off+4])[0]
        hdr = dict(media_class=h[0:64].split(b'\0')[0], res=(g(276), g(280)), page_size=(g(352), g(356)),
                   width=g(372), height=g(376), bpc=g(384), bpp=g(388), bpl=g(392), order=g(396), cspace=g(400),
                   ncolors=g(420), total=g(452), cross=g(456), feed=g(460), size_name=h[1732:1796].split(b'\0')[0])
        w, height, B = hdr['width'], hdr['height'], hdr['bpp'] // 8
        assert hdr['bpl'] == w * B, 'BytesPerLine'
        out, y = bytearray(), 0
        while y < height:
            rep = data[pos]; pos += 1
            line = bytearray()
            while len(line) < w * B:
                c = data[pos]; pos += 1
                if c == 128:                      # rest of line white
                    line += b'\xff' * (w * B - len(line))
                elif c < 128:
                    line += data[pos:pos+B] * (c + 1); pos += B
                else:
                    n = 257 - c; line += data[pos:pos+n*B]; pos += n * B
            assert len(line) == w * B, 'line overflow %d' % len(line)
            for _ in range(rep + 1):
                out += line
            y += rep + 1
        assert y == height, 'row count %d != %d' % (y, height)
        pages.append((hdr, bytes(out)))
    return pages
for name in ('gray', 'rgb'):
    pages = decode(open(sys.argv[1] + '/%s.pwg' % name, 'rb').read())
    expect = open(sys.argv[1] + '/%s.raw' % name, 'rb').read()
    got = b''.join(p for _, p in pages)
    h = pages[0][0]
    ok_hdr = (h['media_class'] == b'PwgRaster' and h['res'] == (300, 300) and h['page_size'] == (595, 842)
              and h['cspace'] == (18 if name == 'gray' else 19) and h['ncolors'] == (1 if name == 'gray' else 3)
              and h['bpc'] == 8 and h['order'] == 0 and h['total'] == 2 and h['cross'] == 1 and h['feed'] == 1
              and h['size_name'] == b'iso_a4_210x297mm')
    print('%s: %d pages, header %s, pixels %s (%d bytes)' % (name, len(pages), 'OK' if ok_hdr else 'BAD %r' % h,
          'IDENTICAL' if got == expect else 'DIFFERENT', len(got)))
