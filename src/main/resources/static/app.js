const byId = id => document.getElementById(id);
const timeZoneFormatters = new Map();
let resources = [];

const escapeHtml = value => String(value).replace(/[&<>"']/g, character => ({
  '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[character]));

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {'Content-Type': 'application/json', ...(options.headers || {})}
  });
  const text = await response.text();
  let body = {};
  if (text) {
    try {
      body = JSON.parse(text);
    } catch (_) {
      body = {};
    }
  }
  if (!response.ok) {
    const error = new Error(body.error || body.message || `HTTP ${response.status}`);
    error.status = response.status;
    error.body = body;
    throw error;
  }
  return body;
}

function status(message, error = false) {
  byId('message').textContent = message;
  byId('message').className = error ? 'error' : 'ok';
}

function formatterFor(timeZone) {
  if (!timeZoneFormatters.has(timeZone)) {
    timeZoneFormatters.set(timeZone, new Intl.DateTimeFormat('en-CA', {
      timeZone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hourCycle: 'h23'
    }));
  }
  return timeZoneFormatters.get(timeZone);
}

function parseLocalDateTime(value, label) {
  const match = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2}))?$/.exec(value);
  if (!match) throw new Error(`${label} must be a valid local date and time.`);
  const parts = {
    year: Number(match[1]), month: Number(match[2]), day: Number(match[3]),
    hour: Number(match[4]), minute: Number(match[5]), second: Number(match[6] || 0)
  };
  const checked = new Date(Date.UTC(parts.year, parts.month - 1, parts.day,
    parts.hour, parts.minute, parts.second));
  if (checked.getUTCFullYear() !== parts.year || checked.getUTCMonth() + 1 !== parts.month
      || checked.getUTCDate() !== parts.day || checked.getUTCHours() !== parts.hour
      || checked.getUTCMinutes() !== parts.minute || checked.getUTCSeconds() !== parts.second) {
    throw new Error(`${label} must be a valid local date and time.`);
  }
  return parts;
}

function zonedParts(instantMilliseconds, timeZone) {
  const parts = {};
  for (const part of formatterFor(timeZone).formatToParts(new Date(instantMilliseconds))) {
    if (part.type !== 'literal') parts[part.type] = Number(part.value);
  }
  return {
    year: parts.year, month: parts.month, day: parts.day,
    hour: parts.hour, minute: parts.minute, second: parts.second
  };
}

function wallMilliseconds(parts) {
  return Date.UTC(parts.year, parts.month - 1, parts.day,
    parts.hour, parts.minute, parts.second);
}

function sameLocalDateTime(left, right) {
  return left.year === right.year && left.month === right.month && left.day === right.day
    && left.hour === right.hour && left.minute === right.minute && left.second === right.second;
}

function possibleInstants(localParts, timeZone) {
  const wallTime = wallMilliseconds(localParts);
  const day = 24 * 60 * 60 * 1000;
  const sampleTimes = [wallTime - 3 * day, wallTime - day, wallTime, wallTime + day, wallTime + 3 * day];
  const offsets = [...new Set(sampleTimes.map(sample => wallMilliseconds(zonedParts(sample, timeZone)) - sample))];
  return offsets
    .map(offset => wallTime - offset)
    .filter(candidate => sameLocalDateTime(zonedParts(candidate, timeZone), localParts))
    .filter((candidate, index, candidates) => candidates.indexOf(candidate) === index)
    .sort((left, right) => left - right);
}

function resolveLocalDateTime(localParts, timeZone) {
  const candidates = possibleInstants(localParts, timeZone);
  const wallTime = wallMilliseconds(localParts);
  if (candidates.length > 0) {
    return {instant: candidates[0], offset: wallTime - candidates[0]};
  }

  const day = 24 * 60 * 60 * 1000;
  const offsetBefore = wallMilliseconds(zonedParts(wallTime - 3 * day, timeZone)) - (wallTime - 3 * day);
  const offsetAfter = wallMilliseconds(zonedParts(wallTime + 3 * day, timeZone)) - (wallTime + 3 * day);
  const gap = offsetAfter - offsetBefore;
  if (gap <= 0) throw new Error(`The selected local time cannot be resolved in ${timeZone}.`);

  const shifted = new Date(wallTime + gap);
  const shiftedParts = {
    year: shifted.getUTCFullYear(), month: shifted.getUTCMonth() + 1, day: shifted.getUTCDate(),
    hour: shifted.getUTCHours(), minute: shifted.getUTCMinutes(), second: shifted.getUTCSeconds()
  };
  const shiftedCandidates = possibleInstants(shiftedParts, timeZone);
  if (shiftedCandidates.length === 0) {
    throw new Error(`The selected local time falls in a timezone gap that cannot be resolved in ${timeZone}.`);
  }
  return {instant: shiftedCandidates[0], offset: offsetBefore};
}

function pad(value) {
  return String(value).padStart(2, '0');
}

function toOffsetIso(parts, offsetMilliseconds) {
  const offsetMinutes = Math.round(offsetMilliseconds / 60000);
  const sign = offsetMinutes >= 0 ? '+' : '-';
  const absoluteOffset = Math.abs(offsetMinutes);
  const offset = `${sign}${pad(Math.floor(absoluteOffset / 60))}:${pad(absoluteOffset % 60)}`;
  return `${parts.year}-${pad(parts.month)}-${pad(parts.day)}T${pad(parts.hour)}:${pad(parts.minute)}:${pad(parts.second)}${offset}`;
}

function localValueToOffsetIso(value, timeZone, label) {
  try {
    const localParts = parseLocalDateTime(value, label);
    const resolved = resolveLocalDateTime(localParts, timeZone);
    return toOffsetIso(localParts, resolved.offset);
  } catch (error) {
    if (error instanceof RangeError) throw new Error(`Timezone ${timeZone} is not supported by this browser.`);
    throw error;
  }
}

function formatConflictTimestamp(value, timeZone) {
  const instant = new Date(value);
  if (Number.isNaN(instant.getTime())) return 'time unavailable';
  try {
    return new Intl.DateTimeFormat(undefined, {
      timeZone, month: 'short', day: 'numeric', year: 'numeric',
      hour: '2-digit', minute: '2-digit'
    }).format(instant);
  } catch (_) {
    return instant.toISOString();
  }
}

function conflictMessage(error, timeZone) {
  if (error.status !== 409 || !Array.isArray(error.body?.conflicts) || error.body.conflicts.length === 0) {
    return null;
  }
  const details = error.body.conflicts.map(conflict => {
    const index = Number.isInteger(conflict.occurrenceIndex) ? conflict.occurrenceIndex : '?';
    const start = formatConflictTimestamp(conflict.startsAt, timeZone);
    const end = formatConflictTimestamp(conflict.endsAt, timeZone);
    return `- Occurrence ${index}: ${start}–${end}`;
  });
  return ['Reservation series could not be created.', '', 'Conflicting occurrences:',
    ...details, '', 'No reservations were created.'].join('\n');
}

async function loadResources() {
  resources = await api('/api/resources');
  byId('resource-count').textContent = `${resources.filter(resource => resource.active).length} available`;
  byId('resource-list').innerHTML = resources.map(resource => `<article class="resource-card" data-testid="resource-card"><span class="pill ${resource.active ? '' : 'unavailable'}">${resource.active ? escapeHtml(resource.type) : 'UNAVAILABLE'}</span><h3>${escapeHtml(resource.name)}</h3><p>${escapeHtml(resource.building)} · ${resource.floorNumber === 0 ? 'Ground' : `Floor ${resource.floorNumber}`}</p><small>${escapeHtml(resource.code)}</small></article>`).join('');
  byId('resource').innerHTML = resources.filter(resource => resource.active).map(resource => `<option value="${resource.id}">${escapeHtml(resource.name)} (${escapeHtml(resource.code)})</option>`).join('');
}

async function loadReservations() {
  const bookings = await api('/api/reservations');
  const active = bookings.filter(booking => booking.status === 'PENDING' || booking.status === 'CONFIRMED');
  byId('reservations').innerHTML = active.length ? active.map(booking => `<div class="row" data-testid="booking-row"><div><strong>${escapeHtml(booking.title)}</strong><small>${escapeHtml(booking.resourceName)} · ${booking.seriesId ? 'Series recurring' : 'One-time'} · ${escapeHtml(booking.status)}</small></div><button data-cancel-id="${booking.id}" class="ghost" type="button">Cancel</button></div>`).join('') : 'No active reservations.';
}

byId('booking-type').addEventListener('change', () => {
  byId('series-options').hidden = byId('booking-type').value !== 'series';
});

byId('reservation-form').addEventListener('submit', async event => {
  event.preventDefault();
  const type = byId('booking-type').value;
  const startValue = byId('start').value;
  const endValue = byId('end').value;
  if (!startValue || !endValue) return status('Please choose valid dates.', true);

  try {
    let booking;
    if (type === 'series') {
      const timeZone = byId('series-timezone').value;
      const payload = {
        resourceId: Number(byId('resource').value),
        title: byId('title').value,
        startsAt: localValueToOffsetIso(startValue, timeZone, 'Start'),
        endsAt: localValueToOffsetIso(endValue, timeZone, 'End'),
        timezone: timeZone,
        frequency: 'WEEKLY',
        occurrences: Number(byId('series-occurrences').value)
      };
      booking = await api('/api/reservation-series', {method: 'POST', body: JSON.stringify(payload)});
      status(`Series ${booking.seriesId} created with ${booking.reservations.length} occurrences.`);
    } else {
      const start = new Date(startValue);
      const end = new Date(endValue);
      if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
        return status('Please choose valid dates.', true);
      }
      booking = await api('/api/reservations', {
        method: 'POST',
        body: JSON.stringify({
          resourceId: Number(byId('resource').value),
          title: byId('title').value,
          startsAt: start.toISOString(),
          endsAt: end.toISOString()
        })
      });
      status(`Reservation ${booking.id} created: ${booking.status}`);
    }
    byId('title').value = '';
    await loadReservations();
  } catch (error) {
    const detailedConflict = type === 'series' ? conflictMessage(error, byId('series-timezone').value) : null;
    status(detailedConflict || error.message, true);
  }
});

byId('reservations').addEventListener('click', async event => {
  const id = event.target.getAttribute('data-cancel-id');
  if (!id) return;
  try {
    await api(`/api/reservations/${id}/cancel`, {method: 'POST'});
    status('Reservation cancelled.');
    await loadReservations();
  } catch (error) {
    status(error.message, true);
  }
});

byId('refresh').addEventListener('click', () => loadReservations().catch(error => status(error.message, true)));
Promise.all([loadResources(), loadReservations()]).catch(error => status(error.message, true));
