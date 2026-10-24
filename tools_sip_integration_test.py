import socket, subprocess, time, re, hashlib, sys, threading, queue, os

KT='/tmp/claude-0/kt/kotlinc/lib'
cp='/tmp/claude-0/h/out:'+KT+'/kotlin-stdlib.jar'
SRV=('127.0.0.1',25060)

class Server:
    def __init__(self, mode):
        self.p=subprocess.Popen(['java','-cp',cp,'com.asterisk.voip.core.MainKt',mode],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        self.lines=queue.Queue(); self.all=[]
        threading.Thread(target=self.rd,daemon=True).start()
        self.wait('READY'); time.sleep(0.8)
    def rd(self):
        for l in self.p.stdout:
            self.all.append(l.rstrip()); self.lines.put(l.rstrip())
    def wait(self,s,t=10):
        end=time.time()+t
        while time.time()<end:
            try:
                l=self.lines.get(timeout=0.2)
                if s in l: return l
            except queue.Empty: pass
        raise Exception('timeout waiting '+s)
    def cmd(self,c): self.p.stdin.write(c+'\n'); self.p.stdin.flush()

def md5(s): return hashlib.md5(s.encode()).hexdigest()

class UA:
    def __init__(s, ext, pw, port):
        s.ext=ext; s.pw=pw; s.port=port; s.sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        s.sock.bind(('127.0.0.1',port)); s.sock.settimeout(3); s.cseq=1; s.tag='t'+ext
    def send(s,m): s.sock.sendto(m.encode('latin1'),SRV)
    def recv(s,t=3):
        s.sock.settimeout(t)
        d,_=s.sock.recvfrom(65535); return d.decode('latin1')
    def recv_until(s, pred, t=5):
        end=time.time()+t
        while time.time()<end:
            try: m=s.recv(max(0.1,end-time.time()))
            except socket.timeout: break
            if pred(m): return m
        raise Exception('%s: expected message not received'%s.ext)
    def register(s, expires=3600):
        callid='reg-'+s.ext
        def build(auth=None):
            h=f"REGISTER sip:127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:{s.port};branch=z9hG4bKr{s.cseq}\r\nMax-Forwards: 70\r\nFrom: <sip:{s.ext}@127.0.0.1>;tag={s.tag}\r\nTo: <sip:{s.ext}@127.0.0.1>\r\nCall-ID: {callid}\r\nCSeq: {s.cseq} REGISTER\r\nContact: <sip:{s.ext}@127.0.0.1:{s.port}>\r\nExpires: {expires}\r\n"
            if auth: h+="Authorization: "+auth+"\r\n"
            return h+"Content-Length: 0\r\n\r\n"
        s.send(build()); r=s.recv()
        assert ' 401 ' in r.split('\r\n')[0], r
        nonce=re.search(r'nonce="([^"]+)"',r).group(1)
        s.cseq+=1
        uri='sip:127.0.0.1'; nc='00000001'; cn='abc'
        ha1=md5(f'{s.ext}:asterisk:{s.pw}'); ha2=md5(f'REGISTER:{uri}')
        resp=md5(f'{ha1}:{nonce}:{nc}:{cn}:auth:{ha2}')
        auth=f'Digest username="{s.ext}", realm="asterisk", nonce="{nonce}", uri="{uri}", response="{resp}", algorithm=MD5, qop=auth, nc={nc}, cnonce="{cn}"'
        s.send(build(auth)); r=s.recv(); s.cseq+=1
        return r

def hdr(m,n):
    r=re.search(r'(?im)^'+n+r':\s*(.*)$',m); return r.group(1).strip() if r else None
def body(m): return m.split('\r\n\r\n',1)[1] if '\r\n\r\n' in m else ''
def first(m): return m.split('\r\n')[0]

SDP_OFFER=("v=0\r\no=- 1 1 IN IP4 127.0.0.1\r\ns=-\r\nc=IN IP4 127.0.0.1\r\nt=0 0\r\n"
 "m=audio 7000 RTP/AVP 0 8 101\r\na=rtpmap:0 PCMU/8000\r\na=rtpmap:101 telephone-event/8000\r\na=candidate:1 1 UDP 2130706431 10.9.9.9 7000 typ host\r\na=ice-ufrag:abcd\r\na=sendrecv\r\n"
 "m=video 7002 RTP/AVPF 96\r\na=rtpmap:96 VP8/90000\r\na=rtcp-fb:96 nack pli\r\na=rtcp:7003\r\na=candidate:2 1 UDP 2130706431 10.9.9.9 7002 typ host\r\na=sendrecv\r\n")
SDP_ANS=SDP_OFFER.replace('7000','8000').replace('7002','8002').replace('7003','8003')

def fails(msg): print('FAIL:',msg); return 1

def run_call(srv, mode_expect_relay):
    a=UA('1001','pw1',6001); b=UA('1002','pw2',6002)
    for u in (a,b):
        r=u.register(); assert first(r).startswith('SIP/2.0 200'), r
        assert 'expires=120' in r, r  # clamped refresh
    callid='call-'+str(time.time())
    inv=(f"INVITE sip:1002@127.0.0.1 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:6001;branch=z9hG4bKinv1\r\nMax-Forwards: 70\r\nFrom: <sip:1001@127.0.0.1>;tag=ta\r\nTo: <sip:1002@127.0.0.1>\r\nCall-ID: {callid}\r\nCSeq: 1 INVITE\r\nContact: <sip:1001@127.0.0.1:6001>\r\nContent-Type: application/sdp\r\nContent-Length: {len(SDP_OFFER)}\r\n\r\n{SDP_OFFER}")
    a.send(inv)
    t=a.recv_until(lambda m: ' 100 ' in first(m))
    got=b.recv_until(lambda m: first(m).startswith('INVITE'))
    assert 'Record-Route: <sip:127.0.0.1:25060;lr>' in got, got
    vias=re.findall(r'(?im)^Via:\s*(.*)$',got); assert len(vias)==2 and 'z9hG4bKsv' in vias[0], vias
    sdp=body(got); print('--- INVITE SDP seen by callee ---'); print(sdp)
    ports=re.findall(r'm=(audio|video) (\d+)',sdp)
    if mode_expect_relay:
        assert 'a=candidate' not in sdp and 'ice-ufrag' not in sdp, 'ICE not stripped'
        assert ports[0][1]!='7000' and ports[1][1]!='7002', ports
        assert 'a=rtcp:' in sdp and 'a=rtcp:7003' not in sdp
    else:
        assert 'm=audio 7000' in sdp and 'm=video 7002' in sdp and 'a=candidate' in sdp, 'direct SDP must be untouched'
    b.send(f"SIP/2.0 180 Ringing\r\nVia: {vias[0]}\r\nVia: {vias[1]}\r\nFrom: {hdr(got,'From')}\r\nTo: {hdr(got,'To')};tag=tb\r\nCall-ID: {callid}\r\nCSeq: 1 INVITE\r\nRecord-Route: <sip:127.0.0.1:25060;lr>\r\nContent-Length: 0\r\n\r\n")
    r=a.recv_until(lambda m: ' 180 ' in first(m)); assert len(re.findall(r'(?im)^Via:',r))==1, r
    ok=(f"SIP/2.0 200 OK\r\nVia: {vias[0]}\r\nVia: {vias[1]}\r\nFrom: {hdr(got,'From')}\r\nTo: {hdr(got,'To')};tag=tb\r\nCall-ID: {callid}\r\nCSeq: 1 INVITE\r\nRecord-Route: <sip:127.0.0.1:25060;lr>\r\nContact: <sip:1002@127.0.0.1:6002>\r\nContent-Type: application/sdp\r\nContent-Length: {len(SDP_ANS)}\r\n\r\n{SDP_ANS}")
    b.send(ok)
    r=a.recv_until(lambda m: first(m).startswith('SIP/2.0 200'))
    asdp=body(r); print('--- 200 SDP seen by caller ---'); print(asdp)
    aports=re.findall(r'm=(audio|video) (\d+)',asdp)
    if mode_expect_relay:
        assert 'a=candidate' not in asdp and aports[0][1]!='8000' and aports[1][1]!='8002', aports
    else:
        assert 'm=audio 8000' in asdp
    # ACK from caller (via Route -> server)
    a.send(f"ACK sip:1002@127.0.0.1:6002 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:6001;branch=z9hG4bKack1\r\nRoute: <sip:127.0.0.1:25060;lr>\r\nMax-Forwards: 70\r\nFrom: <sip:1001@127.0.0.1>;tag=ta\r\nTo: <sip:1002@127.0.0.1>;tag=tb\r\nCall-ID: {callid}\r\nCSeq: 1 ACK\r\nContent-Length: 0\r\n\r\n")
    ackm=b.recv_until(lambda m: first(m).startswith('ACK')); assert 'Route:' not in ackm, 'own Route not stripped'
    if mode_expect_relay:
        # media: a-side server ports (what the CALLER sends to) = aports ; b-side = ports
        ca_vid=int(aports[1][1]); cb_vid=int(ports[1][1]); ca_aud=int(aports[0][1]); cb_aud=int(ports[0][1])
        rtp=lambda: socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
        A_v=rtp(); A_v.bind(('127.0.0.1',7002)); A_c=rtp(); A_c.bind(('127.0.0.1',7003)); A_a=rtp(); A_a.bind(('127.0.0.1',7000))
        B_v=rtp(); B_v.bind(('127.0.0.1',8002)); B_c=rtp(); B_c.bind(('127.0.0.1',8003)); B_a=rtp(); B_a.bind(('127.0.0.1',8000))
        for s_ in (A_v,A_c,A_a,B_v,B_c,B_a): s_.settimeout(2)
        # callee sends first (latch B), then caller
        B_v.sendto(b'B-video-latch',('127.0.0.1',cb_vid)); B_c.sendto(b'B-rtcp',('127.0.0.1',int(re.search(r'a=rtcp:(\d+)',sdp.split('m=video')[1]).group(1))))
        B_a.sendto(b'B-audio',('127.0.0.1',cb_aud))
        time.sleep(0.2)
        A_v.sendto(b'\x80\x60'+b'V'*1200,('127.0.0.1',ca_vid))   # big "video" packet A->B
        A_a.sendto(b'A-audio',('127.0.0.1',ca_aud))
        A_c.sendto(b'A-rtcp-PLI',('127.0.0.1',int(re.search(r'a=rtcp:(\d+)',asdp.split('m=video')[1]).group(1))))
        d,src=B_v.recvfrom(4096); assert len(d)==1202, len(d)
        assert B_a.recvfrom(4096)[0]==b'A-audio'
        assert B_c.recvfrom(4096)[0]==b'A-rtcp-PLI', 'rtcp not relayed'
        # reverse
        B_v.sendto(b'\x80\x60'+b'W'*1000,('127.0.0.1',cb_vid))
        d,_=A_v.recvfrom(4096)
        if len(d)!=1002: d,_=A_v.recvfrom(4096)
        assert len(d)==1002, len(d)
        assert A_a.recvfrom(4096)[0]==b'B-audio'
        print('RTP/RTCP audio+video relayed both ways OK')
    # re-INVITE hold from callee via Route
    hold=SDP_ANS.replace('a=sendrecv','a=sendonly')
    b.send(f"INVITE sip:1001@127.0.0.1:6001 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:6002;branch=z9hG4bKre1\r\nRoute: <sip:127.0.0.1:25060;lr>\r\nMax-Forwards: 70\r\nFrom: <sip:1002@127.0.0.1>;tag=tb\r\nTo: <sip:1001@127.0.0.1>;tag=ta\r\nCall-ID: {callid}\r\nCSeq: 1 INVITE\r\nContact: <sip:1002@127.0.0.1:6002>\r\nContent-Type: application/sdp\r\nContent-Length: {len(hold)}\r\n\r\n{hold}")
    re_inv=a.recv_until(lambda m: first(m).startswith('INVITE'))
    if mode_expect_relay: assert 'm=audio '+aports[0][1] in body(re_inv), body(re_inv)
    # BYE from callee
    b.send(f"BYE sip:1001@127.0.0.1:6001 SIP/2.0\r\nVia: SIP/2.0/UDP 127.0.0.1:6002;branch=z9hG4bKbye\r\nRoute: <sip:127.0.0.1:25060;lr>\r\nMax-Forwards: 70\r\nFrom: <sip:1002@127.0.0.1>;tag=tb\r\nTo: <sip:1001@127.0.0.1>;tag=ta\r\nCall-ID: {callid}\r\nCSeq: 2 BYE\r\nContent-Length: 0\r\n\r\n")
    r=b.recv_until(lambda m: ' 200 ' in first(m) and 'BYE' in m)
    a.recv_until(lambda m: first(m).startswith('BYE'))
    a.sock.close(); b.sock.close()
    if mode_expect_relay:
        for s_ in (A_v,A_c,A_a,B_v,B_c,B_a): s_.close()
    time.sleep(0.5)

def main():
    rc=0
    srv=Server('relay')
    try:
        print('=== RELAY mode ==='); run_call(srv, True)
        srv.cmd('peers'); print(srv.wait('PEERS'))
        # persistence: stop + start; registrations must come back without clients re-registering
        srv.cmd('stop'); srv.wait('STOPPED'); time.sleep(0.5)
        srv.cmd('start'); srv.wait('STARTED true'); time.sleep(0.5)
        srv.cmd('peers'); l=srv.wait('PEERS'); print('after restart:',l)
        assert "1001" in l and "1002" in l, 'registrations lost after restart'
        srv.cmd('mode direct'); srv.wait('MODE')
        print('=== DIRECT mode ==='); run_call(srv, False)
        srv.cmd('mode auto'); srv.wait('MODE')
        print('=== AUTO mode (same IP -> direct) ==='); run_call(srv, False)
        print('ALL TESTS PASSED')
    except Exception as e:
        import traceback; traceback.print_exc(); rc=1
        print('\n'.join(srv.all[-40:]))
    finally:
        srv.cmd('quit'); time.sleep(0.3); srv.p.kill()
    sys.exit(rc)
main()
