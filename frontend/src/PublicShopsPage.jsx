/**
 * Public shop directory — no login required.
 * Routes:
 *   /shops          → ShopListPage
 *   /shops/:shopId  → ShopDetailPage
 */
import { lazy, Suspense, useEffect, useState } from 'react';
import { Link, Route, Routes, useNavigate, useParams } from 'react-router-dom';
import {
  ArrowLeft, ArrowRight, ExternalLink, LoaderCircle,
  MapPin, Navigation, Phone, Search, Store,
} from 'lucide-react';

import { getShopDetail, getNearbyGrouped, reverseGeocode, searchShops } from './publicShopDirectory.js';
import { normalizePublicNearbyResult, normalizePublicShopDetail, normalizePublicShopList } from './orderPageData.js';
import { formatPeso } from './formatters.js';
import { CEBU_MUNICIPALITIES } from './cebuMunicipalities.js';

// ── Leaflet map (lazy, used only on detail page when coords exist) ──────────
const ShopPinMap = lazy(() => import('./ShopPinMap.jsx'));

// ── Shared UI ───────────────────────────────────────────────────────────────
function LoadingSpinner({ label = 'Loading…' }) {
  return (
    <div className="flex items-center justify-center gap-3 py-16 text-sm text-slate-500" role="status">
      <LoaderCircle size={20} className="animate-spin text-teal-600" aria-hidden="true" />
      {label}
    </div>
  );
}

function ErrorMessage({ message, onRetry }) {
  return (
    <div role="alert" className="flex flex-wrap items-start justify-between gap-3 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800">
      <span>{message}</span>
      {onRetry && <button type="button" onClick={onRetry} className="font-semibold underline underline-offset-2">Try again</button>}
    </div>
  );
}

// ── Distance formatting ──────────────────────────────────────────────────────
export function distanceLabel(km) {
  if (km == null) return null;
  if (km < 1) return `${Math.round(km * 1000)} m away`;
  return `${Number(km).toFixed(1)} km away`;
}

function formatMunicipalityProvince(shop) {
  return [shop?.municipality, shop?.province].filter(Boolean).join(', ');
}

function ShopCard({ shop }) {
  const dist = distanceLabel(shop.distanceKm);
  const locationText = formatMunicipalityProvince(shop);
  return (
    <Link
      to={`/shops/${shop.shopId}`}
      className="group flex flex-col rounded-3xl border border-slate-200 bg-white p-5 shadow-sm transition hover:border-teal-300 hover:shadow-md"
    >
      <div className="flex items-start gap-3">
        <span className="mt-0.5 grid h-10 w-10 shrink-0 place-items-center rounded-xl bg-teal-50 text-teal-600">
          <Store size={19} aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <h3 className="truncate font-semibold text-slate-900 group-hover:text-teal-700">{shop.name}</h3>
          {dist && (
            <p className="mt-0.5 text-xs font-semibold text-teal-600">{dist}</p>
          )}
          {locationText && (
            <p className="mt-0.5 truncate text-xs text-slate-500">{locationText}</p>
          )}
        </div>
      </div>
      <p className="mt-3 flex items-start gap-2 text-sm leading-5 text-slate-600">
        <MapPin size={14} className="mt-0.5 shrink-0 text-slate-400" aria-hidden="true" />
        <span className="line-clamp-2">{shop.address || '—'}</span>
      </p>
      {shop.contactNumber && (
        <p className="mt-2 flex items-center gap-2 text-sm text-slate-500">
          <Phone size={13} className="shrink-0 text-slate-400" aria-hidden="true" />
          {shop.contactNumber}
        </p>
      )}
      <span className="mt-4 inline-flex items-center gap-1 text-xs font-semibold text-teal-600 group-hover:underline">
        View details <ArrowRight size={13} />
      </span>
    </Link>
  );
}

// ── Shop list page ───────────────────────────────────────────────────────────
export function ShopListPage() {
  const [query, setQuery] = useState('');
  const [submitted, setSubmitted] = useState('');
  const [callerCoords, setCallerCoords] = useState(null); // { lat, lng }
  const [geoStatus, setGeoStatus] = useState('');
  const [geoBusy, setGeoBusy] = useState(false);
  const [showMunicipalityFallback, setShowMunicipalityFallback] = useState(false);
  const [manualMunicipality, setManualMunicipality] = useState('');
  const [nearbyResult, setNearbyResult] = useState(null);
  const [nearbyLoading, setNearbyLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [shops, setShops] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const fetchShops = (search, pg) => {
    setLoading(true);
    setError('');
    searchShops({ search, page: pg })
      .then((data) => setShops(normalizePublicShopList(data)))
      .catch((err) => setError(err.message || 'Could not load shops.'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (nearbyResult || showMunicipalityFallback) return;
    fetchShops(submitted, page);
  }, [submitted, page, nearbyResult, showMunicipalityFallback]);

  const loadNearby = async ({ lat, lng, municipality }) => {
    setNearbyLoading(true);
    setError('');
    try {
      const raw = await getNearbyGrouped({ lat, lng, municipality });
      const normalized = normalizePublicNearbyResult(raw);
      setNearbyResult(normalized);
      if (normalized.customerMunicipality) {
        setManualMunicipality(normalized.customerMunicipality);
        setGeoStatus(`Showing shops near ${normalized.customerMunicipality}.`);
      } else {
        setGeoStatus('Showing nearby shops.');
      }
    } catch (err) {
      setError(err.message || 'Could not load nearby shops.');
    } finally {
      setNearbyLoading(false);
    }
  };

  const handleSearch = (e) => {
    e.preventDefault();
    setNearbyResult(null);
    setCallerCoords(null);
    setShowMunicipalityFallback(false);
    setManualMunicipality('');
    setGeoStatus('');
    setPage(0);
    setSubmitted(query.trim());
  };

  const handleNearMe = () => {
    if (!navigator?.geolocation) {
      setShowMunicipalityFallback(true);
      setGeoStatus('Your browser does not support location access. Select your municipality below.');
      return;
    }
    setGeoBusy(true);
    setError('');
    setGeoStatus('Requesting your location…');
    navigator.geolocation.getCurrentPosition(
      async (pos) => {
        const coords = { lat: pos.coords.latitude, lng: pos.coords.longitude };
        setCallerCoords(coords);
        let detectedMunicipality = '';
        try {
          const geo = await reverseGeocode(coords);
          detectedMunicipality = geo?.municipality || '';
        } catch {
          detectedMunicipality = '';
        }
        if (!detectedMunicipality) {
          setShowMunicipalityFallback(true);
        }
        await loadNearby({ lat: coords.lat, lng: coords.lng, municipality: detectedMunicipality });
        if (!detectedMunicipality) {
          setGeoStatus('Could not detect your municipality automatically. Showing nearby shops — select your municipality below for grouped results.');
        }
        setGeoBusy(false);
      },
      (err) => {
        setShowMunicipalityFallback(true);
        if (err?.code === 1) {
          setGeoStatus('Location permission was denied. Select your municipality below to find nearby shops.');
        } else if (err?.code === 3) {
          setGeoStatus('Location request timed out. Select your municipality below to find nearby shops.');
        } else {
          setGeoStatus('Your location could not be determined. Select your municipality below to find nearby shops.');
        }
        setGeoBusy(false);
      },
      { enableHighAccuracy: true, timeout: 10000 },
    );
  };

  const handleMunicipalityChange = (e) => {
    const selected = e.target.value;
    setManualMunicipality(selected);
    if (!selected) return;
    loadNearby({ lat: callerCoords?.lat, lng: callerCoords?.lng, municipality: selected });
  };

  const clearNearMe = () => {
    setCallerCoords(null);
    setNearbyResult(null);
    setShowMunicipalityFallback(false);
    setManualMunicipality('');
    setGeoStatus('');
    setError('');
    setPage(0);
  };

  const nearMeActive = Boolean(nearbyResult || callerCoords || showMunicipalityFallback);
  const isFirstPage = page === 0;
  const isLastPage = Array.isArray(shops) && shops.length < 20;
  const isBusy = loading || nearbyLoading;

  return (
    <div className="flex flex-1 flex-col bg-slate-50">
      <div className="mx-auto w-full max-w-5xl flex-1 px-5 py-8 sm:px-8">
        {/* Header */}
        <div className="mb-7">
          <p className="text-xs font-bold uppercase tracking-[0.16em] text-teal-700">Public directory</p>
          <h1 className="mt-2 text-2xl font-semibold tracking-tight text-slate-900 sm:text-3xl">Find a laundry shop</h1>
          <p className="mt-2 text-sm leading-6 text-slate-500">Only active, platform-verified shops are listed. Search by name or address, or use &ldquo;Near me&rdquo; for the closest shops.</p>
        </div>

        {/* Search bar */}
        <form onSubmit={handleSearch} className="mb-4 flex flex-wrap gap-2">
          <label className="relative flex-1 min-w-[200px]">
            <span className="sr-only">Search shops by name or address</span>
            <Search size={17} className="absolute left-3 top-3 text-slate-400" aria-hidden="true" />
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="Search by shop name or address…"
              className="min-h-11 w-full rounded-xl border border-slate-200 bg-white pl-10 pr-3 text-sm outline-none focus:border-teal-500 focus:ring-4 focus:ring-teal-50"
            />
          </label>
          <button type="submit" className="inline-flex min-h-11 items-center gap-2 rounded-xl bg-teal-600 px-4 text-sm font-semibold text-white hover:bg-teal-700">
            <Search size={16} />Search
          </button>
          <button
            type="button"
            onClick={nearMeActive ? clearNearMe : handleNearMe}
            disabled={geoBusy}
            className={`inline-flex min-h-11 items-center gap-2 rounded-xl border px-4 text-sm font-semibold transition disabled:opacity-60 ${nearMeActive ? 'border-teal-300 bg-teal-50 text-teal-800 hover:bg-teal-100' : 'border-slate-200 bg-white text-slate-700 hover:bg-slate-50'}`}
          >
            {geoBusy ? <LoaderCircle size={16} className="animate-spin" /> : <Navigation size={16} />}
            {nearMeActive ? 'Clear near me' : 'Near me'}
          </button>
        </form>

        {/* Geo status & manual municipality fallback */}
        {geoStatus && (
          <p role="status" className="mb-4 rounded-xl bg-teal-50 px-4 py-2.5 text-sm text-teal-800">
            {geoStatus}
          </p>
        )}

        {(showMunicipalityFallback || nearbyResult) && (
          <div className="mb-6 rounded-2xl border border-slate-200 bg-white p-4">
            <label className="flex flex-wrap items-center gap-3 text-sm font-medium text-slate-700">
              <span>Municipality / City:</span>
              <select
                aria-label="Select your municipality or city"
                value={manualMunicipality}
                onChange={handleMunicipalityChange}
                className="min-h-10 rounded-xl border border-slate-300 bg-white px-3 text-sm text-slate-900 outline-none focus:border-teal-500 focus:ring-4 focus:ring-teal-50"
              >
                <option value="">Select municipality…</option>
                {CEBU_MUNICIPALITIES.map((name) => (
                  <option key={name} value={name}>{name}</option>
                ))}
              </select>
            </label>
          </div>
        )}

        {/* Results */}
        {error && (
          <ErrorMessage
            message={error}
            onRetry={() => (nearMeActive ? loadNearby({ lat: callerCoords?.lat, lng: callerCoords?.lng, municipality: manualMunicipality }) : fetchShops(submitted, page))}
          />
        )}

        {isBusy ? <LoadingSpinner label="Finding shops…" /> : !error && nearbyResult ? (
          <div className="space-y-8">
            <section>
              <h2 className="text-lg font-semibold text-slate-900">
                {nearbyResult.customerMunicipality
                  ? `Shops near me (${nearbyResult.customerMunicipality})`
                  : 'Shops near your location'}
              </h2>
              {nearbyResult.ownMunicipalityShops.length > 0 ? (
                <div className="mt-4 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                  {nearbyResult.ownMunicipalityShops.map((shop) => (
                    <ShopCard key={shop.shopId} shop={shop} />
                  ))}
                </div>
              ) : (
                <p className="mt-3 rounded-2xl border border-dashed border-slate-200 bg-white px-5 py-6 text-sm text-slate-600">
                  {nearbyResult.customerMunicipality
                    ? `No registered shops in ${nearbyResult.customerMunicipality} yet`
                    : 'No registered shops found in your immediate location yet.'}
                </p>
              )}
            </section>

            {nearbyResult.nearbyGroups.length > 0 && (
              <section className="space-y-6">
                <h2 className="text-lg font-semibold text-slate-900">
                  {nearbyResult.customerMunicipality
                    ? `Shops also near ${nearbyResult.customerMunicipality}`
                    : 'Nearby municipalities'}
                </h2>
                {nearbyResult.nearbyGroups.map((group) => (
                  <div key={group.municipality} className="space-y-3">
                    <h3 className="text-sm font-bold uppercase tracking-wide text-teal-700">
                      Shops in {group.municipality}
                    </h3>
                    <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                      {group.shops.map((shop) => (
                        <ShopCard key={shop.shopId} shop={shop} />
                      ))}
                    </div>
                  </div>
                ))}
              </section>
            )}

            {nearbyResult.ownMunicipalityShops.length === 0 && nearbyResult.nearbyGroups.length === 0 && (
              <div className="rounded-3xl border border-dashed border-slate-200 bg-white px-6 py-10 text-center">
                <p className="text-sm text-slate-500">No nearby active shops were found within range. Try selecting another municipality or clearing the Near&nbsp;me filter.</p>
              </div>
            )}
          </div>
        ) : !error && !showMunicipalityFallback && (
          <>
            {shops?.length === 0 ? (
              <div className="mt-8 rounded-3xl border border-dashed border-slate-200 bg-white px-6 py-12 text-center">
                <span className="mx-auto grid h-12 w-12 place-items-center rounded-2xl bg-teal-50 text-teal-600">
                  <Store size={22} aria-hidden="true" />
                </span>
                <h2 className="mt-4 text-base font-semibold text-slate-900">No shops found</h2>
                <p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-slate-500">
                  {submitted ? `No active shops match "${submitted}".` : 'No active shops are listed yet.'} Try a different search or use Near&nbsp;me.
                </p>
              </div>
            ) : (
              <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
                {shops?.map((shop) => (
                  <ShopCard key={shop.shopId} shop={shop} />
                ))}
              </div>
            )}

            {/* Pagination */}
            {(shops?.length > 0 || page > 0) && (
              <div className="mt-8 flex items-center justify-between gap-3">
                <button
                  type="button"
                  disabled={isFirstPage}
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  className="inline-flex min-h-9 items-center gap-1 rounded-xl border border-slate-200 bg-white px-4 text-sm font-semibold text-slate-700 disabled:opacity-40 hover:bg-slate-50"
                >
                  <ArrowLeft size={15} />Previous
                </button>
                <span className="text-sm text-slate-500">Page {page + 1}</span>
                <button
                  type="button"
                  disabled={isLastPage}
                  onClick={() => setPage((p) => p + 1)}
                  className="inline-flex min-h-9 items-center gap-1 rounded-xl border border-slate-200 bg-white px-4 text-sm font-semibold text-slate-700 disabled:opacity-40 hover:bg-slate-50"
                >
                  Next <ArrowRight size={15} />
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}

// ── Shop detail page ─────────────────────────────────────────────────────────
function ShopDetailPage() {
  const { shopId } = useParams();
  const navigate = useNavigate();
  const [shop, setShop] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    setLoading(true); setError('');
    getShopDetail(shopId)
      .then((data) => setShop(normalizePublicShopDetail(data)))
      .catch((err) => setError(err.status === 404 ? 'This shop was not found or is not currently active.' : (err.message || 'Could not load shop details.')))
      .finally(() => setLoading(false));
  }, [shopId]);

  const hasCoords = shop?.latitude != null && shop?.longitude != null;
  const directionsUrl = hasCoords
    ? `https://www.google.com/maps/dir/?api=1&destination=${shop.latitude},${shop.longitude}`
    : null;
  const areaLabel = formatMunicipalityProvince(shop);

  return (
    <div className="flex flex-1 flex-col bg-slate-50">
      <div className="mx-auto w-full max-w-3xl flex-1 px-5 py-8 sm:px-8">
        <button
          type="button"
          onClick={() => navigate(-1)}
          className="mb-6 inline-flex items-center gap-2 text-sm font-semibold text-teal-700 hover:text-teal-800"
        >
          <ArrowLeft size={16} />Back to shops
        </button>

        {loading && <LoadingSpinner label="Loading shop details…" />}
        {error && <ErrorMessage message={error} />}

        {!loading && !error && shop && (
          <>
            <div className="rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
              {/* Shop header */}
              <div className="flex flex-wrap items-start justify-between gap-4">
                <div className="flex items-start gap-4">
                  <span className="grid h-12 w-12 shrink-0 place-items-center rounded-2xl bg-teal-50 text-teal-600">
                    <Store size={22} aria-hidden="true" />
                  </span>
                  <div>
                    <p className="text-xs font-bold uppercase tracking-[0.16em] text-teal-700">Active shop</p>
                    <h1 className="mt-1 text-2xl font-semibold text-slate-900">{shop.name}</h1>
                  </div>
                </div>
                {directionsUrl && (
                  <a
                    href={directionsUrl}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="inline-flex min-h-10 items-center gap-2 rounded-xl bg-teal-600 px-4 text-sm font-semibold text-white hover:bg-teal-700"
                  >
                    <Navigation size={16} />Get directions
                    <ExternalLink size={13} className="opacity-70" />
                  </a>
                )}
              </div>

              {/* Details */}
              <dl className="mt-6 grid gap-4 sm:grid-cols-2">
                <div>
                  <dt className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-slate-400">
                    <MapPin size={13} />Address
                  </dt>
                  <dd className="mt-1 text-sm leading-6 text-slate-700">
                    {shop.address || '—'}
                    {areaLabel && <span className="block text-xs text-slate-500">{areaLabel}</span>}
                  </dd>
                </div>
                {shop.contactNumber && (
                  <div>
                    <dt className="flex items-center gap-1.5 text-xs font-bold uppercase tracking-wide text-slate-400">
                      <Phone size={13} />Contact
                    </dt>
                    <dd className="mt-1 text-sm text-slate-700">{shop.contactNumber}</dd>
                  </div>
                )}
                {hasCoords && (
                  <div>
                    <dt className="text-xs font-bold uppercase tracking-wide text-slate-400">Coordinates</dt>
                    <dd className="mt-1 text-sm font-mono text-slate-500">
                      {shop.latitude.toFixed(5)}, {shop.longitude.toFixed(5)}
                    </dd>
                  </div>
                )}
              </dl>

              {/* Map */}
              {hasCoords && (
                <div className="mt-6">
                  <Suspense fallback={<div className="flex h-[280px] items-center justify-center rounded-xl bg-slate-50 text-sm text-slate-400">Loading map…</div>}>
                    <ShopPinMap lat={shop.latitude} lng={shop.longitude} name={shop.name} />
                  </Suspense>
                </div>
              )}
            </div>

            {/* Services */}
            <div className="mt-5 rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
              <h2 className="font-semibold text-slate-900">Services &amp; prices</h2>
              {shop.services.length === 0 ? (
                <p className="mt-4 text-sm text-slate-500">No active services listed yet.</p>
              ) : (
                <ul className="mt-4 divide-y divide-slate-100">
                  {shop.services.map((svc, idx) => (
                    <li key={`${svc.name}-${idx}`} className="rounded-xl border border-slate-100 p-4">
                      <div className="flex flex-wrap items-start justify-between gap-3">
                        <div>
                          <p className="text-sm font-semibold text-slate-900">{svc.name}</p>
                          <p className="mt-1 text-xs text-slate-500">{svc.category || 'Other'} · {String(svc.billingType || svc.unit).replaceAll('_', ' ')}</p>
                          {svc.capacityKg != null && <p className="mt-1 text-xs text-slate-500">Capacity: up to {svc.capacityKg} kg per load</p>}
                          {svc.notes && <p className="mt-2 text-sm leading-5 text-slate-600">{svc.notes}</p>}
                        </div>
                        <span className="whitespace-nowrap text-sm font-semibold text-teal-700">{formatPeso(svc.referencePrice)} {svc.billingType === 'FLAT' ? 'flat rate' : `per ${svc.unit}`}</span>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              <p className="mt-4 text-xs leading-5 text-slate-400">
                Prices are reference rates set by the shop owner and may vary. Contact the shop for confirmation.
              </p>
            </div>
            <div className="mt-5 rounded-3xl border border-slate-200 bg-white p-6 shadow-sm sm:p-8">
              <h2 className="font-semibold text-slate-900">Add-ons</h2>
              {!shop.addOns.length ? <p className="mt-4 text-sm text-slate-500">No active add-ons listed yet.</p> : <ul className="mt-4 grid gap-3 sm:grid-cols-2">{shop.addOns.map((item, idx) => <li key={`${item.name}-${idx}`} className="rounded-xl border border-slate-100 p-4"><div className="flex items-start justify-between gap-3"><div><p className="font-semibold text-slate-900">{item.name}</p><p className="mt-1 text-xs text-slate-500">{item.category} · per {item.unit}</p></div><span className="whitespace-nowrap text-sm font-semibold text-teal-700">{formatPeso(item.price)}</span></div></li>)}</ul>}
              <p className="mt-4 text-xs text-slate-400">Only active catalog items are shown. Stock information is private to the shop.</p>
            </div>
          </>
        )}
      </div>
    </div>
  );
}

// ── Router entry point ───────────────────────────────────────────────────────
export default function PublicShopsPage() {
  return (
    <Routes>
      <Route index element={<ShopListPage />} />
      <Route path=":shopId" element={<ShopDetailPage />} />
    </Routes>
  );
}
