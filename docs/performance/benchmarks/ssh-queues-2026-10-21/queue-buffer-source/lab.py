#!/usr/bin/env python3
"""Isolated Linux SSH lab. Only its own namespace, veth and temporary keys are used."""
import json, mmap, os, pathlib, signal, socket, struct, subprocess, sys, threading, time
from echo_observer import EchoJournal, serve_echo, snapshot as echo_snapshot
from relay_buffers import configure_send_buffer

BASE = pathlib.Path('/tmp/vtunnel-ssh-network')
NS = 'vtsshbench'
HOST_DEV, PEER_DEV = 'vtssh-host', 'vtssh-peer'
ADDRESS = '10.205.33.2'
PROFILES = {
    'deep': ('1000', ['20ms'], [], '4mbit', '8mbit'),
    'shallow': ('32', ['20ms'], [], '4mbit', '8mbit'),
    'mobile-jitter': ('128', ['60ms', '25ms', 'distribution', 'normal'],
                      ['loss', 'random', '0.3%'], '3mbit', '12mbit'),
}

def run(*args, check=True):
    return subprocess.run(args, check=check, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE).stdout

def spawn(args, name):
    with (BASE / (name + '.log')).open('w') as output:
        process = subprocess.Popen(args, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
    (BASE / (name + '.pid')).write_text(str(process.pid))

def down():
    if BASE.exists():
        for path in BASE.glob('*.pid'):
            try:
                pid = int(path.read_text())
                # Never terminate an unrelated PID reused since a previous run.
                command = pathlib.Path(f'/proc/{pid}/cmdline').read_bytes()
                if b'lab.py' in command or str(BASE).encode() in command:
                    os.killpg(pid, signal.SIGTERM)
            except (OSError, ValueError):
                pass
            path.unlink(missing_ok=True)
    for value in run('ip', 'netns', 'pids', NS, check=False).split():
        try: os.kill(int(value), signal.SIGTERM)
        except OSError: pass
    run('ip', 'netns', 'del', NS, check=False)
    run('ip', 'link', 'del', HOST_DEV, check=False)
    for name in ['host_key','host_key.pub','client_key','client_key.pub','sshd.conf']:
        (BASE/name).unlink(missing_ok=True)

def shape(profile, seed=None):
    if seed is not None and (not isinstance(seed, int) or not 0 <= seed < 2147483647):
        raise ValueError('Invalid network seed')
    if profile == 'unshaped':
        run('tc','qdisc','del','dev',HOST_DEV,'root',check=False)
        run('ip','netns','exec',NS,'tc','qdisc','del','dev',PEER_DEV,'root',check=False)
        return
    if profile not in PROFILES: raise ValueError('Unknown network profile')
    limit, delay, loss, up_rate, down_rate = PROFILES[profile]
    for index, (prefix, dev, rate) in enumerate([([], HOST_DEV, up_rate), (['ip','netns','exec',NS], PEER_DEV, down_rate)]):
        seed_args = [] if seed is None else ['seed', str(seed + index)]
        run(*(prefix + ['tc','qdisc','replace','dev',dev,'root','netem','limit',limit,'delay'] + delay + loss + ['rate',rate] + seed_args))

def up():
    down()
    BASE.mkdir(mode=0o700, exist_ok=True)
    for name in ['host_key','client_key']:
        if not (BASE/name).exists():
            run('ssh-keygen','-q','-t','rsa','-b','2048','-m','PEM','-N','','-f',str(BASE/name))
    run('ip','netns','add',NS)
    run('ip','link','add',HOST_DEV,'type','veth','peer','name',PEER_DEV)
    run('ip','link','set',PEER_DEV,'netns',NS)
    run('ip','addr','add','10.205.33.1/30','dev',HOST_DEV)
    run('ip','link','set',HOST_DEV,'up')
    for command in [['link','set','lo','up'],['addr','add',ADDRESS+'/30','dev',PEER_DEV],['link','set',PEER_DEV,'up']]:
        run('ip','netns','exec',NS,'ip',*command)
    config = BASE/'sshd.conf'
    config.write_text(f'''Port 22022
ListenAddress {ADDRESS}
HostKey {BASE}/host_key
PidFile {BASE}/sshd.pid
AuthorizedKeysFile {BASE}/client_key.pub
StrictModes no
PermitRootLogin yes
PubkeyAuthentication yes
PasswordAuthentication no
KbdInteractiveAuthentication no
UsePAM yes
AllowTcpForwarding local
PermitOpen 127.0.0.1:17001 127.0.0.1:17002 127.0.0.1:17003
X11Forwarding no
PermitTTY no
LogLevel ERROR
''')
    spawn(['ip','netns','exec',NS,'/usr/sbin/sshd','-D','-e','-f',str(config)], 'sshd-process')
    spawn(['ip','netns','exec',NS,sys.executable,__file__,'services'], 'services')
    (BASE / 'relay-send-buffer').write_text('0')
    spawn([sys.executable,__file__,'relay'], 'relay')
    shape('deep')
    time.sleep(.5)
    print(json.dumps({'ssh_port':19080,'namespace':NS,'down_mbit':8,'up_mbit':4,'one_way_delay_ms':20,'fifo_packets':1000}))

def copy(source, destination):
    try:
        while True:
            data = source.recv(65536)
            if not data: break
            destination.sendall(data)
    except OSError:
        pass
    finally:
        try: destination.shutdown(socket.SHUT_WR)
        except OSError: pass

def relay():
    with socket.create_server(('127.0.0.1',19080)) as listener:
        while True:
            client,_ = listener.accept()
            def connection(client):
                with client, socket.create_connection((ADDRESS,22022)) as server:
                    client.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
                    server.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
                    requested = int((BASE / "relay-send-buffer").read_text())
                    details = configure_send_buffer(server, requested)
                    temporary = BASE / "relay-socket.tmp"
                    temporary.write_text(json.dumps(details))
                    temporary.replace(BASE / "relay-socket.json")
                    other=threading.Thread(target=copy,args=(client,server),daemon=True);other.start()
                    copy(server,client);other.join(timeout=2)
            threading.Thread(target=connection,args=(client,),daemon=True).start()

def services(address='127.0.0.1', offset=0):
    import fcntl
    # Shared-memory counters: upload counts bytes received at the actual remote sink.
    counter_file = (BASE / f'counters-{offset}.bin').open('w+b')
    counter_file.write(bytes(16)); counter_file.flush()
    counters = mmap.mmap(counter_file.fileno(), 16)
    counter_lock = threading.Lock()
    echo_journal = EchoJournal(BASE / f"echo-{offset}.json")
    def count(index, size):
        with counter_lock:
            fcntl.flock(counter_file, fcntl.LOCK_EX)
            try:
                position = index * 8
                previous = struct.unpack_from('<Q', counters, position)[0]
                struct.pack_into('<Q', counters, position, previous + size)
            finally:
                fcntl.flock(counter_file, fcntl.LOCK_UN)
    def listen(port):
        def serve(client):
            with client:
                client.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
                try:
                    if port == 17001 + offset:
                        serve_echo(client, echo_journal)
                    elif port == 17002 + offset:
                        if not client.recv(1): return
                        data=b'x'*65536
                        while True:
                            client.sendall(data)
                            count(1, len(data))
                    else:
                        while True:
                            data=client.recv(65536)
                            if not data: break
                            if port == 17003 + offset: count(0, len(data))
                except OSError: pass
        with socket.create_server((address,port)) as listener:
            while True:
                client,_=listener.accept()
                threading.Thread(target=serve,args=(client,),daemon=True).start()
    for port in [17001+offset,17002+offset,17003+offset]: threading.Thread(target=listen,args=(port,),daemon=True).start()
    threading.Event().wait()

def direct_relay():
    def listen(local, remote):
        with socket.create_server(('127.0.0.1',local)) as listener:
            while True:
                client,_=listener.accept()
                def connection(client):
                    with client, socket.create_connection((ADDRESS,remote)) as server:
                        client.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
                        server.setsockopt(socket.IPPROTO_TCP,socket.TCP_NODELAY,1)
                        other=threading.Thread(target=copy,args=(client,server),daemon=True);other.start()
                        copy(server,client);other.join(timeout=2)
                threading.Thread(target=connection,args=(client,),daemon=True).start()
    for index in [1,2,3]: threading.Thread(target=listen,args=(19080+index,17100+index),daemon=True).start()
    threading.Event().wait()

def reset_phase(profile, seed=None, relay_buffer=0):
    if not isinstance(relay_buffer, int) or not 0 <= relay_buffer <= 1048576:
        raise ValueError("Invalid private relay buffer size")
    (BASE / "relay-send-buffer").write_text(str(relay_buffer))
    (BASE / "relay-socket.json").unlink(missing_ok=True)
    # Tear down all endpoints in this private namespace to discard old TCP queues.
    for value in run('ip','netns','pids',NS).split():
        try: os.kill(int(value),signal.SIGKILL)
        except OSError: pass
    time.sleep(.1)
    # Unlike qdisc replace, delete/add discards old queues and counters.
    shape('unshaped')
    for offset in [0, 100]:
        (BASE / f'echo-{offset}.json').unlink(missing_ok=True)
    shape(profile, seed)
    spawn(['ip','netns','exec',NS,'/usr/sbin/sshd','-D','-e','-f',str(BASE/'sshd.conf')], 'sshd-process')
    spawn(['ip','netns','exec',NS,sys.executable,__file__,'services'], 'services')
    spawn(['ip','netns','exec',NS,sys.executable,__file__,'direct-services'], 'direct-services')
    time.sleep(.4)

def read_counters(path):
    import fcntl
    if not path.exists():
        return (0, 0)
    with path.open('rb') as stream:
        # Writers update mmap in place: the control process must not observe half an update.
        fcntl.flock(stream, fcntl.LOCK_SH)
        return struct.unpack('<QQ', stream.read(16))


def control():
    with socket.create_server(('127.0.0.1',19084)) as listener:
        while True:
            client,_=listener.accept()
            with client:
                command=client.makefile('rb').readline(128).decode().strip()
                if command == 'ECHO':
                    observation = echo_snapshot(BASE / "echo-0.json")
                    try:
                        observation["relay"] = json.loads((BASE / "relay-socket.json").read_text())
                    except FileNotFoundError:
                        observation["relay"] = None
                    client.sendall((json.dumps(observation)+'\n').encode());continue
                if command == 'STATS':
                    stats = {}
                    for offset in [0, 100]:
                        path = BASE / f'counters-{offset}.bin'
                        uploaded, downloaded = read_counters(path)
                        stats[str(offset)] = {'upload_received': uploaded, 'download_sent': downloaded}
                    client.sendall((json.dumps(stats)+'\n').encode());continue
                parts = command.split()
                if (len(parts) not in (2,3,4) or parts[0] != 'RESET' or
                    parts[1] not in [*PROFILES, 'unshaped'] or
                    (len(parts) >= 3 and (not parts[2].isdigit() or not 0 <= int(parts[2]) < 2147483647)) or
                    (len(parts) == 4 and (not parts[3].isdigit() or not 0 <= int(parts[3]) <= 1048576))):
                    client.sendall(b'ERROR\n');continue
                try:
                    reset_phase(parts[1], int(parts[2]) if len(parts) >= 3 else None,
                                int(parts[3]) if len(parts) == 4 else 0);client.sendall(b'OK\n')
                except Exception as error:
                    client.sendall(('ERROR '+type(error).__name__+'\n').encode())

if __name__ == '__main__':
    action=sys.argv[1]
    if action=='up': up()
    elif action=='down': down()
    elif action=='shape': shape(sys.argv[2])
    elif action=='services': services()
    elif action=='relay': relay()
    elif action=='direct-services': services(ADDRESS,100)
    elif action=='direct-relay': direct_relay()
    elif action=='start-direct':
        spawn(['ip','netns','exec',NS,sys.executable,__file__,'direct-services'], 'direct-services')
        spawn([sys.executable,__file__,'direct-relay'], 'direct-relay')
    elif action=='control': control()
    elif action=='start-control': spawn([sys.executable,__file__,'control'], 'control')
    elif action=='stats':
        print(run('tc','-s','qdisc','show','dev',HOST_DEV))
        print(run('ip','netns','exec',NS,'tc','-s','qdisc','show','dev',PEER_DEV))
