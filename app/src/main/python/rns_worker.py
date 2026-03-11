import RNS
import LXMF
import threading
import time
from RNS.Interfaces.Interface import Interface

destination = None
lxmf_router = None
_start_done = threading.Event()
_start_result = {"addr": None, "error": None}

class AndroidBTInterface(Interface):
    BITRATE_GUESS = 9600

    def __init__(self, owner, name, socket):
        self.name = name
        self.rxb = 0
        self.txb = 0
        self.online = False
        self.IN  = True
        self.OUT = True
        self.FWD = False
        self.RPT = False
        self.owner = owner
        self._socket = socket
        self.online = True
        threading.Thread(target=self._read_loop, daemon=True).start()

    def _read_loop(self):
        while self.online:
            try:
                data = self._socket.read(512)
                if data and len(data) > 0:
                    self.rxb += len(data)
                    self.processIncoming(bytes(data))
            except Exception as e:
                RNS.log(f"BT read error: {e}")
                self.online = False

    def processOutgoing(self, data):
        try:
            self._socket.write(data)
            self.txb += len(data)
        except Exception as e:
            RNS.log(f"BT write error: {e}")

def message_received(message):
    RNS.log(f"MSG from {RNS.prettyhexrep(message.source_hash)}: {message.content_as_string()}")

def _rns_main(bt_socket_wrapper):
    """Runs in its own thread so signal handling works correctly."""
    global destination, lxmf_router
    try:
        reticulum = RNS.Reticulum(configdir=None, loglevel=RNS.LOG_DEBUG)

        iface = AndroidBTInterface(reticulum, "RNodeBT", bt_socket_wrapper)
        RNS.Transport.interfaces.append(iface)

        identity = RNS.Identity()
        lxmf_router = LXMF.LXMRouter(
            storagepath="/data/data/com.example.rnshello/files/lxmf",
            autopeer=True
        )
        destination = lxmf_router.register_delivery_identity(
            identity,
            display_name="RNS Hello Android"
        )
        lxmf_router.register_delivery_callback(message_received)
        destination.announce()

        addr = RNS.prettyhexrep(destination.hash)
        RNS.log(f"LXMF address: {addr}")
        _start_result["addr"] = addr

    except Exception as e:
        RNS.log(f"RNS start error: {e}")
        _start_result["error"] = str(e)
    finally:
        _start_done.set()

def start(bt_socket_wrapper):
    """Called from Kotlin - launches RNS in its own thread and waits for result."""
    _start_done.clear()
    t = threading.Thread(target=_rns_main, args=(bt_socket_wrapper,), daemon=True)
    t.start()
    # Wait up to 30 seconds for RNS to initialise
    _start_done.wait(timeout=30)
    if _start_result["error"]:
        return f"Error: {_start_result['error']}"
    return _start_result["addr"] or "Timeout - RNS did not start"

def send_hello(dest_hash_hex):
    global lxmf_router, destination
    if not lxmf_router or not destination:
        return "Not connected"
    try:
        msg = LXMF.LXMessage(
            destination_hash=bytes.fromhex(dest_hash_hex),
            source=destination,
            content="Hello World",
            title="Hello",
            desired_method=LXMF.LXMessage.DIRECT
        )
        lxmf_router.handle_outbound(msg)
        return "Sent"
    except Exception as e:
        return f"Error: {e}"

def get_address():
    global destination
    return RNS.prettyhexrep(destination.hash) if destination else "Not initialized"
