/**
 * Read-only Leaflet map showing a single shop pin.
 * No API key required — uses OpenStreetMap tiles.
 */
import { useEffect, useRef } from 'react';

export default function ShopPinMap({ lat, lng, name }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    import('leaflet').then((L) => {
      delete L.default.Icon.Default.prototype._getIconUrl;
      L.default.Icon.Default.mergeOptions({
        iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
        iconUrl:        'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
        shadowUrl:      'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
      });

      const map = L.default.map(containerRef.current, { zoomControl: true, scrollWheelZoom: false })
        .setView([lat, lng], 16);
      mapRef.current = map;

      L.default.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19,
      }).addTo(map);

      const marker = L.default.marker([lat, lng]).addTo(map);
      if (name) marker.bindPopup(name).openPopup();
    });

    return () => {
      if (mapRef.current) {
        mapRef.current.remove();
        mapRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div
      ref={containerRef}
      style={{ height: '280px', width: '100%', borderRadius: '0.75rem', zIndex: 0 }}
      aria-label={`Map showing location of ${name || 'shop'}`}
    />
  );
}
