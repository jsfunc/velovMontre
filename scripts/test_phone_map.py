#!/usr/bin/env python3
"""Exercise the real bundled Leaflet UI in Chromium via CDP, with NO real tile requests.

Requires Python websocket-client and a Chromium instance started with:
chromium --headless --disable-gpu --remote-debugging-port=9223 \
  --remote-allow-origins=http://localhost:9223 --user-data-dir=/tmp/velov-map-browser about:blank
Run: python3 scripts/test_phone_map.py
"""
import base64
import functools
import http.server
import json
from pathlib import Path
import threading
import time
import urllib.request

import websocket

ROOT = Path(__file__).resolve().parents[1]


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, *args):
        pass


class Browser:
    def __init__(self):
        request = urllib.request.Request("http://localhost:9223/json/new?about:blank", method="PUT")
        with urllib.request.urlopen(request) as response:
            target = json.load(response)
        self.ws = websocket.create_connection(target["webSocketDebuggerUrl"], origin="http://localhost:9223", timeout=10)
        self.sequence = 0
        self.fail_tiles = False
        self.exceptions = []
        self.tile_requests = 0

    def command(self, method, params=None):
        self.sequence += 1
        number = self.sequence
        self.ws.send(json.dumps(dict(id=number, method=method, params=params or {})))
        while True:
            message = json.loads(self.ws.recv())
            if message.get("method") == "Fetch.requestPaused":
                self.tile_requests += 1
                # Intercept before any network request; never automate requests to OSM.
                params = dict(requestId=message["params"]["requestId"])
                if self.fail_tiles:
                    params["errorReason"] = "InternetDisconnected"
                    action = "Fetch.failRequest"
                else:
                    blank = b'<svg xmlns="http://www.w3.org/2000/svg" width="256" height="256"><path fill="#e4e8e5" d="M0 0h256v256H0z"/></svg>'
                    params.update(responseCode=200, responseHeaders=[dict(name="Content-Type", value="image/svg+xml")],
                                  body=base64.b64encode(blank).decode())
                    action = "Fetch.fulfillRequest"
                self.sequence += 1
                self.ws.send(json.dumps(dict(id=self.sequence, method=action, params=params)))
            elif message.get("method") == "Runtime.exceptionThrown":
                self.exceptions.append(message["params"])
            if message.get("id") == number:
                if "error" in message:
                    raise RuntimeError(message["error"])
                return message.get("result", {})

    def js(self, expression):
        result = self.command("Runtime.evaluate", dict(expression=expression, returnByValue=True, awaitPromise=True))
        if "exceptionDetails" in result:
            raise AssertionError(result["exceptionDetails"])
        return result["result"].get("value")

    def wait(self, expression):
        deadline = time.monotonic() + 8
        while time.monotonic() < deadline:
            if self.js(expression):
                return
            time.sleep(.05)
        raise AssertionError("Timed out: " + expression)


def main():
    handler = functools.partial(QuietHandler, directory=str(ROOT / "phone/src/main/assets/map"))
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    browser = Browser()
    try:
        browser.command("Page.enable")
        browser.command("Runtime.enable")
        browser.command("Fetch.enable", dict(patterns=[dict(urlPattern="https://tile.openstreetmap.org/*", requestStage="Request")]))
        browser.command("Emulation.setDeviceMetricsOverride", dict(width=379, height=600, deviceScaleFactor=2, mobile=True))
        browser.command("Page.navigate", dict(url=f"http://127.0.0.1:{server.server_port}/index.html"))
        browser.wait("typeof window.showStations === 'function'")
        groups = json.loads((ROOT / "phone/src/test/fixtures/map-stations.json").read_text())
        browser.js("window.showStations(" + json.dumps(groups[0]) + ")")
        browser.wait("document.querySelectorAll('.station-icon').length === 8")
        browser.wait("document.querySelectorAll('.leaflet-tile-loaded').length > 0")
        assert browser.js("document.getElementById('notice').hidden")
        assert browser.js("document.querySelectorAll('.station-id').length") == 0
        assert browser.js("getComputedStyle(document.querySelector('.badge')).backgroundColor") == "rgba(0, 0, 0, 0)"
        assert browser.js("document.querySelector('.counts').textContent") == "2 1 5"
        assert browser.js("document.querySelector('.leaflet-control-attribution').textContent").startswith("© OpenStreetMap")
        assert browser.js("Array.from(document.querySelectorAll('.station-icon')).every(e => {const r=e.getBoundingClientRect();return r.left>=0 && r.top>=0 && r.right<=innerWidth && r.bottom<=innerHeight;})")
        clear_labels = """Array.from(document.querySelectorAll('.badge')).map(e=>e.getBoundingClientRect()).every((a,i,all)=>
            all.slice(i+1).every(b=>a.right<=b.left||b.right<=a.left||a.bottom<=b.top||b.bottom<=a.top))"""
        assert browser.js(clear_labels), "Group 1 labels overlap"
        # Real click opens the full-name detail, including all three metric labels.
        browser.js("document.querySelector('.station-icon').click()")
        browser.wait("document.querySelector('.detail h2')?.textContent === " + json.dumps(groups[0]["stations"][0]["name"]))
        assert "Places libres" in browser.js("document.querySelector('.detail').textContent")
        browser.js("map.closePopup(); map.setView([45.729,4.827],17,{animate:false});undefined")
        position = browser.js("[map.getCenter().lat,map.getCenter().lng,map.getZoom()]")
        groups[0]["stations"][0]["value"] = "_-_-_"
        groups[0]["stations"][0]["color"] = "#ff929a"
        browser.js("window.showStations(" + json.dumps(groups[0]) + ")")
        assert browser.js(f"map.getCenter().distanceTo([{position[0]},{position[1]}]) < 1.5 && map.getZoom() === {position[2]}")
        assert browser.js("document.querySelector('.counts').textContent") == "_ _ _"
        assert browser.js("document.querySelector('.counts').style.color") == "rgb(208, 0, 0)"
        browser.js("window.showStations(" + json.dumps(groups[1]) + ")")
        assert browser.js("document.querySelector('.station-icon').title") == groups[1]["stations"][0]["name"]
        assert browser.js(clear_labels), "Group 2 labels overlap"
        assert browser.js("""current.stations.every(s=>{const p=map.latLngToContainerPoint([s.lat,s.lng]);
            return Array.from(document.querySelectorAll('.badge')).every(e=>{const r=e.getBoundingClientRect();
                return p.x<r.left||p.x>r.right||p.y<r.top||p.y>r.bottom;});})"""), "A label covers a station's geographic point"
        browser.command("Emulation.setDeviceMetricsOverride", dict(width=320, height=500, deviceScaleFactor=2, mobile=True))
        browser.js("map.invalidateSize({pan:false});window.fitStations()")
        assert browser.js(clear_labels), "Labels overlap on a narrower phone"
        browser.command("Emulation.setDeviceMetricsOverride", dict(width=379, height=600, deviceScaleFactor=2, mobile=True))
        browser.js("map.invalidateSize({pan:false});window.fitStations()")
        browser.js("window.showStations(" + json.dumps(groups[0]) + ")")
        assert browser.js(f"map.getCenter().distanceTo([{position[0]},{position[1]}]) < 1.5 && map.getZoom() === {position[2]}")
        browser.js("window.fitStations()")
        # A feed name is text, never executable markup.
        groups[0]["stations"][0]["name"] = '<img src=x onerror="window.injected=true"> Quai'
        browser.js("window.showStations(" + json.dumps(groups[0]) + ");document.querySelector('.station-icon').click()")
        browser.wait("document.querySelector('.detail h2')?.textContent === " + json.dumps(groups[0]["stations"][0]["name"]))
        assert not browser.js("Boolean(window.injected) || document.querySelectorAll('.detail img').length > 0")
        browser.js("map.closePopup();undefined")
        # Empty and missing-coordinate groups must not leave old markers behind.
        browser.js('window.showStations({group:0,total:1,missing:1,stations:[]})')
        assert browser.js("document.querySelectorAll('.station-icon').length") == 0
        assert "sans position" in browser.js("document.getElementById('notice').textContent")
        browser.js('window.showStations({group:0,total:0,missing:0,stations:[]})')
        assert "Aucune station" in browser.js("document.getElementById('notice').textContent")
        browser.js("window.showStations(" + json.dumps(groups[1]) + ")")
        browser.wait("document.querySelectorAll('.station-icon').length === 8")
        report = ROOT / "phone/build/reports"
        report.mkdir(parents=True, exist_ok=True)
        browser.wait("!map._panAnim || !map._panAnim._inProgress")
        screenshot = browser.command("Page.captureScreenshot", dict(format="png"))
        (report / "map-preview-fixture.png").write_bytes(base64.b64decode(screenshot["data"]))
        browser.js("document.querySelector('.station-icon').click()")
        browser.wait("!map._panAnim || !map._panAnim._inProgress")
        screenshot = browser.command("Page.captureScreenshot", dict(format="png"))
        (report / "map-detail-fixture.png").write_bytes(base64.b64decode(screenshot["data"]))
        # Simulate a network outage, then recovery, without contacting the provider.
        browser.fail_tiles = True
        browser.js("map.closePopup();map.eachLayer(layer=>{if(layer instanceof L.TileLayer)layer.setUrl('https://tile.openstreetmap.org/{z}/{x}/{y}.png?test=offline')});undefined")
        browser.wait("document.getElementById('notice').textContent.includes('Fond de carte indisponible')")
        assert browser.js("document.querySelectorAll('.station-icon').length") == 8
        browser.fail_tiles = False
        browser.js("map.eachLayer(layer=>{if(layer instanceof L.TileLayer)layer.setUrl('https://tile.openstreetmap.org/{z}/{x}/{y}.png?test=recovery')});undefined")
        browser.wait("document.getElementById('notice').hidden")
        assert not browser.exceptions, browser.exceptions
        print(f"Map browser checks passed; {browser.tile_requests} tile requests substituted locally, none sent to OSM.")
        print(f"Preview (synthetic counts and blank tiles): {report / 'map-preview-fixture.png'}")
    finally:
        browser.command("Page.close")
        browser.ws.close()
        server.shutdown()


if __name__ == "__main__":
    main()
