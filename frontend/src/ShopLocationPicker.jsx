/**
 * Leaflet-based pin picker for the owner shop profile page.
 * No API key required — uses OpenStreetMap tiles.
 */
import { useEffect, useRef } from 'react';

// Leaflet is loaded lazily via dynamic import so it doesn't break SSR-less builds.
// react-leaflet components require a rendered DOM; we use the raw Leaflet API here
// so this component can be used without React strict-mode double-mount issues.

export default function ShopLocationPicker({ lat, lng, onChange }) {
  const containerRef = useRef(null);
  const mapRef = useRef(null);
  const markerRef = useRef(null);

  // Default to Consolacion, Cebu when no coordinates are set yet
  const initialLat = lat != null ? lat : 10.3772;
  const initialLng = lng != null ? lng : 123.9578;

  useEffect(() => {
    if (!containerRef.current || mapRef.current) return;

    // Dynamic import so Leaflet never runs during SSR / Node test environments
    import('leaflet').then((L) => {
      // Fix default marker icon path broken by bundlers
      delete L.default.Icon.Default.prototype._getIconUrl;
      L.default.Icon.Default.mergeOptions({
        iconRetinaUrl: 'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon-2x.png',
        iconUrl:        'https://unpkg.com/leaflet@1.9.4/dist/images/marker-icon.png',
        shadowUrl:      'https://unpkg.com/leaflet@1.9.4/dist/images/marker-shadow.png',
      });

      const map = L.default.map(containerRef.current).setView([initialLat, initialLng], 15);
      mapRef.current = map;

      L.default.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
        attribution: '© <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
        maxZoom: 19,
      }).addTo(map);

      const marker = L.default.marker([initialLat, initialLng], { draggable: true }).addTo(map);
      markerRef.current = marker;

      marker.on('dragend', () => {
        const { lat: newLat, lng: newLng } = marker.getLatLng();
        onChange(parseFloat(newLat.toFixed(7)), parseFloat(newLng.toFixed(7)));
      });

      map.on('click', (event) => {
        const { lat: newLat, lng: newLng } = event.latlng;
        marker.setLatLng([newLat, newLng]);
        onChange(parseFloat(newLat.toFixed(7)), parseFloat(newLng.toFixed(7)));
      });
    });

    return () => {
      if (mapRef.current) {
        mapRef.current.remove();
        mapRef.current = null;
        markerRef.current = null;
      }
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Keep marker in sync when lat/lng change externally (e.g. "Use my location")
  useEffect(() => {
    if (!markerRef.current || lat == null || lng == null) return;
    markerRef.current.setLatLng([lat, lng]);
    if (mapRef.current) mapRef.current.setView([lat, lng], mapRef.current.getZoom());
  }, [lat, lng]);

  return (
    <div
      ref={containerRef}
      style={{ height: '340px', width: '100%', borderRadius: '0.75rem', zIndex: 0 }}
      aria-label="Map: click or drag the pin to set your shop location"
    />
  );
}
