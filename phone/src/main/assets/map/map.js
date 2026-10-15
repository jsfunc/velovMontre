'use strict';
const map = L.map('map', { zoomControl: true, attributionControl: true, fadeAnimation: false }).setView([45.75, 4.85], 13);
map.zoomControl.setPosition('bottomleft');
map.attributionControl.setPrefix(false);
const failedTiles = new Set();
let current = { group: -1, stations: [], total: 0, missing: 0 };
let membership = '';
const markers = new Map();
const views = new Map();
// The same freshness states, with darker ink for contrast over a transparent map label.
const mapColors = { '#64e4c0': '#007f18', '#ffb74d': '#a64b00', '#ff929a': '#d00000' };
L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
  maxZoom: 19, minZoom: 3, keepBuffer: 1,
  attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
}).on('tileerror', event => { failedTiles.add(event.tile); notice(); })
  .on('tileload tileunload', event => { failedTiles.delete(event.tile); notice(); }).addTo(map);

function node(tag, className, text) {
  const el = document.createElement(tag);
  el.className = className;
  if (text !== undefined) el.textContent = text;
  return el;
}
function notice() {
  const messages = [];
  if (!current.total) messages.push('Aucune station dans ce groupe. Utilisez Modifier pour en ajouter.');
  else if (current.missing) messages.push(current.missing + ' station(s) sans position. Elles restent disponibles dans la liste.');
  if (failedTiles.size) messages.push('Fond de carte indisponible. Les données des stations restent affichées.');
  const el = document.getElementById('notice');
  el.textContent = messages.join(' ');
  el.hidden = !messages.length;
}
function details(station) {
  const box = node('div', 'detail');
  box.append(node('h2', '', station.name));
  const values = node('div', 'detail-counts');
  const labels = ['Électriques', 'Classiques', 'Places libres'];
  station.value.split('-').forEach((value, i) => {
    const column = node('div', '');
    column.append(node('strong', '', value), node('span', '', labels[i]));
    values.append(column);
  });
  box.append(values, node('p', 'detail-age', station.freshness || ''));
  return box;
}
window.fitStations = function () {
  if (current.stations.length) map.fitBounds(current.stations.map(s => [s.lat, s.lng]), { padding: [60, 85], maxZoom: 16, animate: false });
};
window.showStations = function (data) {
  const switched = current.group !== data.group;
  if (switched && current.group >= 0) views.set(current.group, { center: map.getCenter(), zoom: map.getZoom(), membership });
  const nextMembership = data.stations.map(s => `${s.id}:${s.lat}:${s.lng}`).sort().join('|');
  const changed = switched || nextMembership !== membership;
  current = data;
  membership = nextMembership;
  const ids = new Set(data.stations.map(s => s.id));
  for (const [id, entry] of markers) {
    if (!ids.has(id)) { entry.marker.remove(); entry.line.remove(); entry.dot.remove(); markers.delete(id); }
  }
  for (const station of data.stations) {
    let entry = markers.get(station.id);
    if (!entry) {
      const badge = node('div', 'badge');
      const counts = node('span', 'counts');
      badge.append(counts);
      const marker = L.marker([station.lat, station.lng], {
        icon: L.divIcon({ className: 'station-icon', html: badge, iconSize: [72, 26], iconAnchor: [36, 32], popupAnchor: [0, 0] }),
        keyboard: true, riseOnHover: true
      }).addTo(map).bindPopup(details(station), { maxWidth: 290 });
      const line = L.polyline([], { color: '#34454e', weight: 1, opacity: .6, interactive: false }).addTo(map);
      const dot = L.circleMarker([station.lat, station.lng], { radius: 2.5, color: '#ffffff', weight: 1,
        fillColor: '#152026', fillOpacity: 1, interactive: false }).addTo(map);
      entry = { marker, badge, counts, line, dot, signature: '' };
      markers.set(station.id, entry);
    }
    entry.marker.setLatLng([station.lat, station.lng]);
    entry.dot.setLatLng([station.lat, station.lng]);
    const signature = JSON.stringify(station);
    if (entry.signature !== signature) {
      entry.counts.textContent = station.value.replaceAll('-', ' ');
      entry.counts.style.color = mapColors[station.color] || '#34454e';
      entry.marker.getElement().setAttribute('aria-label', station.description + '. Afficher le détail.');
      entry.marker.getElement().setAttribute('title', station.name);
      entry.marker.setPopupContent(details(station));
      entry.signature = signature;
    }
  }
  if (changed) {
    map.invalidateSize({ pan: false });
    const saved = views.get(data.group);
    if (switched && saved && saved.membership === membership) map.setView(saved.center, saved.zoom, { animate: false });
    else window.fitStations();
  }
  layoutLabels();
  notice();
};

// Keep the geographic point exact. Only crowded labels move, with a short leader to that point.
function layoutLabels() {
  const size = map.getSize();
  if (size.x < 120 || size.y < 100) return;
  const placed = [{ x: 0, y: size.y-122, w: 62, h: 122 }]; // Keep the zoom buttons clear.
  const pins = current.stations.map(station => {
    const p = map.latLngToContainerPoint([station.lat, station.lng]);
    return { x: p.x-4, y: p.y-4, w: 8, h: 8 };
  });
  const clamp = (value, low, high) => Math.max(low, Math.min(high, value));
  const overlap = (a, b) => Math.max(0, Math.min(a.x+a.w, b.x+b.w) - Math.max(a.x,b.x))
    * Math.max(0, Math.min(a.y+a.h, b.y+b.h) - Math.max(a.y,b.y));
  for (const station of current.stations) {
    const entry = markers.get(station.id);
    if (!entry) continue;
    const point = map.latLngToContainerPoint([station.lat, station.lng]);
    const element = entry.marker.getElement();
    // Off-screen stations should stay off-screen, rather than collecting at the edge.
    if (point.x < 0 || point.y < 0 || point.x > size.x || point.y > size.y) {
      element.style.visibility = 'hidden'; entry.line.setLatLngs([]); continue;
    }
    element.style.visibility = '';
    const width = entry.badge.offsetWidth, height = entry.badge.offsetHeight;
    element.style.width = width + 'px';
    element.style.height = height + 'px';
    const origin = { x: point.x-width/2, y: point.y-height-6 };
    let best, score = Infinity;
    const candidates = [[0,0], [0,height+12], [width/2+6,height/2+6], [-width/2-6,height/2+6]];
    for (let y = -72; y <= 72; y += 6) for (let x = -96; x <= 96; x += 8) candidates.push([x,y]);
    for (const [x,y] of candidates) {
      const rect = { x: clamp(origin.x+x, 8, size.x-width-8), y: clamp(origin.y+y, 8, size.y-height-24), w: width, h: height };
      const edgeX = clamp(point.x, rect.x, rect.x+width), edgeY = clamp(point.y, rect.y, rect.y+height);
      const distance = (point.x-edgeX)**2 + (point.y-edgeY)**2;
      const centerDistance = (point.x-rect.x-width/2)**2 + (point.y-rect.y-height/2)**2;
      const cost = distance + centerDistance*.1 + ((rect.x-origin.x)**2+(rect.y-origin.y)**2)*.001
        + placed.reduce((sum, p) => sum + overlap({x:rect.x-3,y:rect.y-3,w:width+6,h:height+6}, p)*10000, 0)
        + pins.reduce((sum, p) => sum + overlap({x:rect.x-1,y:rect.y-1,w:width+2,h:height+2}, p)*10000, 0);
      if (cost < score) { score = cost; best = rect; }
    }
    placed.push(best);
    element.style.marginLeft = (best.x-point.x) + 'px';
    element.style.marginTop = (best.y-point.y) + 'px';
    const edge = L.point(clamp(point.x, best.x, best.x+width), clamp(point.y, best.y, best.y+height));
    entry.line.setLatLngs(point.distanceTo(edge) > 10 ? [entry.marker.getLatLng(), map.containerPointToLatLng(edge)] : []);
    entry.marker.getPopup().options.offset = L.point(best.x+width/2-point.x, best.y-point.y);
  }
}
map.on('moveend zoomend resize', layoutLabels);
// Android can detach/reuse this view while editing; resizing must not reset the user's pan/zoom.
new ResizeObserver(() => map.invalidateSize({ pan: false })).observe(document.getElementById('map'));
