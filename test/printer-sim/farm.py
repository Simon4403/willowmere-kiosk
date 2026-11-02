"""
A farm of simulated network printers for testing the kiosk's printing code.

Built on h2g2bob/ipp-server (an independent IPP implementation): its request
parser and response encoder are used unchanged; this file only adds printer
personalities, job state machines, TLS, and fixes its body reading so it
honours Content-Length (as real printers do).

Every job received is written to JOBS/<printer>-<n>.json (+ .doc) so the Java
side can check exactly what arrived.
"""
import io
import json
import os
import socket
import socketserver
import ssl
import struct
import sys
import threading

sys.path.insert(0, sys.argv[1])  # ipp-server checkout
from ippserver import server as ippsrv                       # noqa: E402
from ippserver.behaviour import StatelessPrinter, get_job_id  # noqa: E402
from ippserver.constants import SectionEnum, TagEnum, StatusCodeEnum  # noqa: E402
from ippserver.parsers import Integer, Enum, Boolean        # noqa: E402
from ippserver.request import IppRequest                    # noqa: E402

JOBS = sys.argv[2]
CERT = sys.argv[3]
KEY = sys.argv[4]
os.makedirs(JOBS, exist_ok=True)

P = SectionEnum.printer
O = SectionEnum.operation


def res(x, y):
    return struct.pack('>iib', x, y, 3)


def rng(lo, hi):
    return struct.pack('>ii', lo, hi)


class Printer(StatelessPrinter):
    def __init__(self, key, name, formats, *, uuid, accepting=True, state=3, reasons=(b'none',),
                 copies=(1, 99), scaling=True, pwg_types=(), pwg_res=(), job_states=((5, b'job-printing'), (9, b'job-completed-successfully')),
                 forget_jobs=False, print_status=0, path_ok=None):
        super().__init__()
        self.key, self.name, self.formats, self.uuid = key, name, formats, uuid
        self.accepting, self.state, self.reasons = accepting, state, list(reasons)
        self.copies, self.scaling = copies, scaling
        self.pwg_types, self.pwg_res = pwg_types, pwg_res
        self.job_states = list(job_states)
        self.forget_jobs, self.print_status = forget_jobs, print_status
        self.path_ok = path_ok
        self.jobs = {}
        self.next_job = 100
        self.lock = threading.Lock()

    # ---- printer description, on top of the base class's required attributes
    def printer_list_attributes(self):
        a = super().printer_list_attributes()
        a[(P, b'printer-name', TagEnum.name_without_language)] = [self.key.encode()]
        a[(P, b'printer-info', TagEnum.text_without_language)] = [self.name.encode()]
        a[(P, b'printer-make-and-model', TagEnum.text_without_language)] = [b'Simulated ' + self.name.encode()]
        a[(P, b'printer-location', TagEnum.text_without_language)] = [b'Nurses station']
        a[(P, b'printer-uuid', TagEnum.uri)] = [self.uuid.encode()]
        a[(P, b'printer-state', TagEnum.enum)] = [Enum(self.state).bytes()]
        a[(P, b'printer-state-reasons', TagEnum.keyword)] = self.reasons
        a[(P, b'printer-is-accepting-jobs', TagEnum.boolean)] = [Boolean(self.accepting).bytes()]
        a[(P, b'document-format-supported', TagEnum.mime_media_type)] = [f.encode() for f in self.formats]
        a[(P, b'document-format-default', TagEnum.mime_media_type)] = [self.formats[0].encode()]
        a[(P, b'media-supported', TagEnum.keyword)] = [b'iso_a4_210x297mm', b'na_letter_8.5x11in']
        a[(P, b'media-default', TagEnum.keyword)] = [b'iso_a4_210x297mm']
        if self.scaling:
            a[(P, b'print-scaling-supported', TagEnum.keyword)] = [b'auto', b'fit', b'fill', b'none']
        if self.copies:
            a[(P, b'copies-supported', TagEnum.range_of_integer)] = [rng(*self.copies)]
        if self.pwg_types:
            a[(P, b'pwg-raster-document-type-supported', TagEnum.keyword)] = [t.encode() for t in self.pwg_types]
        if self.pwg_res:
            a[(P, b'pwg-raster-document-resolution-supported', TagEnum.resolution)] = [res(r, r) for r in self.pwg_res]
        return a  # (collection skipping is tested separately with hand-built bytes)

    # ---- jobs
    def operation_print_job_response(self, req, docfile):
        doc = docfile.read() if docfile is not None else b''
        if self.print_status:
            return IppRequest(self.version, self.print_status, req.request_id, self.minimal_attributes())
        with self.lock:
            jid = self.next_job
            self.next_job += 1
            self.jobs[jid] = 0

        def one(section, name, tag):
            try:
                v = req.lookup(section, name, tag)
            except KeyError:
                return None
            if tag == TagEnum.integer:
                return [struct.unpack('>i', x)[0] for x in v]
            return [x.decode() for x in v]

        record = {
            'printer': self.key, 'job_id': jid, 'doc_bytes': len(doc),
            'job-name': one(O, b'job-name', TagEnum.name_without_language),
            'document-format': one(O, b'document-format', TagEnum.mime_media_type),
            'requesting-user-name': one(O, b'requesting-user-name', TagEnum.name_without_language),
            'printer-uri': one(O, b'printer-uri', TagEnum.uri),
            'copies': one(SectionEnum.job, b'copies', TagEnum.integer),
            'media': one(SectionEnum.job, b'media', TagEnum.keyword),
            'print-scaling': one(SectionEnum.job, b'print-scaling', TagEnum.keyword),
        }
        base = os.path.join(JOBS, '%s-%d' % (self.key, jid))
        with open(base + '.doc', 'wb') as f:
            f.write(doc)
        with open(base + '.json', 'w') as f:
            json.dump(record, f)
        attrs = self.print_job_attributes(jid, 3, [b'job-incoming'])
        return IppRequest(self.version, StatusCodeEnum.ok, req.request_id, attrs)

    def operation_get_job_attributes_response(self, req, _f):
        jid = get_job_id(req)
        if self.forget_jobs or jid not in self.jobs:
            return IppRequest(self.version, 0x0406, req.request_id, self.minimal_attributes())
        with self.lock:
            n = self.jobs[jid]
            self.jobs[jid] = n + 1
        state, reason = self.job_states[min(n, len(self.job_states) - 1)]
        attrs = self.print_job_attributes(jid, state, [reason])
        attrs[(O, b'job-state-message', TagEnum.text_without_language)] = [reason.replace(b'-', b' ')]
        return IppRequest(self.version, StatusCodeEnum.ok, req.request_id, attrs)


class Handler(ippsrv.IPPRequestHandler):
    def parse_request(self):
        ok = super().parse_request()
        if not ok:
            return ok
        n = int(self.headers.get('content-length') or 0)
        if n and not isinstance(self.rfile, io.BytesIO):
            self.rfile = io.BytesIO(self.rfile.read(n))
        return ok

    def do_POST(self):
        b = self.server.behaviour
        if b.path_ok and self.path != b.path_ok:
            self.send_headers(status=404, content_type='text/plain', content_length=9)
            self.wfile.write(b'not found')
            return
        if self.headers.get('content-type', '').split(';')[0].strip() != 'application/ipp':
            self.send_headers(status=400, content_type='text/plain', content_length=3)
            self.wfile.write(b'bad')
            return
        self.server.hosts_seen.append(self.headers.get('host'))
        self.handle_ipp()


class Server(socketserver.ThreadingMixIn, socketserver.TCPServer):
    allow_reuse_address = True
    daemon_threads = True

    def __init__(self, addr, behaviour, tls=False):
        self.behaviour, self.tls, self.hosts_seen = behaviour, tls, []
        super().__init__(addr, Handler)
        if tls:
            self.ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            self.ctx.load_cert_chain(CERT, KEY)

    def get_request(self):
        sock, addr = super().get_request()
        if self.tls:
            sock = self.ctx.wrap_socket(sock, server_side=True)
        return sock, addr


class WebPage(socketserver.StreamRequestHandler):
    """Something that isn't a printer: an ordinary web server."""
    def handle(self):
        while self.rfile.readline() not in (b'\r\n', b'\n', b''):
            pass
        body = b'<html>Router admin</html>'
        self.wfile.write(b'HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: %d\r\nConnection: close\r\n\r\n' % len(body) + body)


def silent(host, port):
    """Accepts connections and never says anything (some printer firmware on a TLS hello)."""
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((host, port))
    s.listen(16)
    held = []
    while True:
        c, _ = s.accept()
        held.append(c)


def serve(host, port, behaviour, tls=False):
    srv = Server((host, port), behaviour, tls)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


U = 'urn:uuid:%s'
servers = {
    'office':   serve('127.0.0.1', 8631, Printer('office', 'Office PDF', ['application/pdf', 'image/pwg-raster'], uuid=U % 'a1')),
    'pwg':      serve('127.0.0.1', 8632, Printer('pwg', 'Ward PWG', ['image/pwg-raster', 'image/urf'], uuid=U % 'b2',
                                                pwg_types=('black_1', 'sgray_8', 'srgb_8'), pwg_res=(300, 600))),
    'nopaper':  serve('127.0.0.1', 8633, Printer('nopaper', 'Empty tray', ['application/pdf'], uuid=U % 'c3',
                                                accepting=False, state=5, reasons=(b'media-empty-error',))),
    'aborts':   serve('127.0.0.1', 8634, Printer('aborts', 'Fussy', ['application/pdf'], uuid=U % 'd4',
                                                job_states=((5, b'job-printing'), (8, b'document-format-error')))),
    'nocopies': serve('127.0.0.1', 8635, Printer('nocopies', 'No copies', ['application/pdf'], uuid=U % 'e5', copies=None, scaling=False)),
    'tls':      serve('127.0.0.1', 8636, Printer('tls', 'Secure jam', ['application/pdf'], uuid=U % 'f6',
                                                job_states=((6, b'media-jam-error'), (6, b'media-jam-error'), (9, b'job-completed-successfully'))), tls=True),
    'ps':       serve('127.0.0.1', 8637, Printer('ps', 'Old PostScript', ['application/postscript'], uuid=U % 'g7')),
    'forgets':  serve('127.0.0.1', 8638, Printer('forgets', 'Forgetful', ['application/pdf'], uuid=U % 'h8', forget_jobs=True)),
    'busy':     serve('127.0.0.1', 8639, Printer('busy', 'Busy', ['application/pdf'], uuid=U % 'i9', print_status=0x0507)),
    'cancel':   serve('127.0.0.1', 8640, Printer('cancel', 'Cancelled', ['application/pdf'], uuid=U % 'j0',
                                                job_states=((5, b'job-printing'), (7, b'job-canceled-at-device')))),
    # "Add by IP" targets: the kiosk always tries port 631.
    'ip_plain': serve('127.0.0.2', 631, Printer('ip_plain', 'By IP plain', ['application/pdf'], uuid=U % 'k1', path_ok='/ipp/print')),
    'ip_tls':   serve('127.0.0.3', 631, Printer('ip_tls', 'By IP secure', ['application/pdf'], uuid=U % 'l2', path_ok='/ipp/print'), tls=True),
    'ip_path':  serve('127.0.0.4', 631, Printer('ip_path', 'By IP odd path', ['application/pdf'], uuid=U % 'm3', path_ok='/ipp')),
}
web = socketserver.ThreadingTCPServer(('127.0.0.7', 631), WebPage)
threading.Thread(target=web.serve_forever, daemon=True).start()
threading.Thread(target=silent, args=('127.0.0.6', 631), daemon=True).start()

print('FARM READY', flush=True)
threading.Event().wait()
