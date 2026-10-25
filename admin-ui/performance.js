(() => {
  const base = '/_objectstore/admin';
  const state = document.getElementById('perf-state');
  const charts = [
    ['perf-requests-chart', [
      { type: 'line', x: 'time', y: 'requests', name: 'Requests/s' },
      { type: 'line', x: 'time', y: 'errors', name: 'Errors/s' }
    ]],
    ['perf-transfer-chart', [
      { type: 'line', x: 'time', y: 'inbound', name: 'Inbound KiB/s' },
      { type: 'line', x: 'time', y: 'outbound', name: 'Outbound KiB/s' }
    ]],
    ['perf-cpu-chart', [
      { type: 'line', x: 'time', y: 'cpu', name: 'CPU %' }
    ]],
    ['perf-memory-chart', [
      { type: 'line', x: 'time', y: 'memory', name: 'RSS MiB' },
      { type: 'line', x: 'time', y: 'heap', name: 'Heap MiB' }
    ]]
  ].map(([id, series]) => {
    const chart = document.getElementById(id);
    chart.series = series;
    return chart;
  });
  const history = [];
  let previous = null;
  let polling = false;
  let memoryFallback = false;

  function show(id, value) {
    document.getElementById(id).textContent = value;
  }

  function bytes(value, suffix = '') {
    if (!Number.isFinite(value) || value < 0) return '—';
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let index = 0;
    while (value >= 1024 && index < units.length - 1) {
      value /= 1024;
      index++;
    }
    return (index === 0 ? Math.round(value) : value.toFixed(1)) + ' ' + units[index] + suffix;
  }

  function rate(value) {
    return Number.isFinite(value) ? value.toFixed(value < 10 ? 2 : 1) : '—';
  }

  function counterDelta(current, before, key) {
    return Math.max(0, current[key] - before[key]);
  }

  function updateCharts() {
    document.querySelector('.perf-charts').hidden = history.length === 0;
    for (const chart of charts)
      chart.data = chart.id === 'perf-cpu-chart'
        ? history.filter(point => point.cpu !== null) : history;
  }

  async function poll() {
    if (polling || document.hidden) return;
    polling = true;
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch(base + '/performance.json', {
        cache: 'no-store', credentials: 'same-origin', signal: controller.signal
      });
      if (response.redirected) {
        location.assign(base + '/login');
        return;
      }
      if (!response.ok) throw new Error('Metrics unavailable');
      if (!response.headers.get('Content-Type')?.includes('application/json'))
        throw new Error('Unexpected metrics response');
      const sample = await response.json();
      const now = performance.now();
      const rss = sample.rssBytes >= 0 ? sample.rssBytes : sample.heapBytes + sample.nonHeapBytes;
      if (sample.rssBytes < 0 && !memoryFallback) {
        memoryFallback = true;
        document.querySelector('#perf-ram').previousElementSibling.textContent = 'JVM memory used';
        charts[3].series = [
          { type: 'line', x: 'time', y: 'memory', name: 'JVM used MiB' },
          { type: 'line', x: 'time', y: 'heap', name: 'Heap MiB' }
        ];
      }
      show('perf-ram', bytes(rss));
      show('perf-gc', Number(sample.gcCount).toLocaleString());
      show('perf-inflight', Number(sample.inFlight).toLocaleString());
      if (previous && sample.requests < previous.sample.requests) {
        history.length = 0;
        updateCharts();
      }
      if (previous && sample.requests >= previous.sample.requests) {
        const seconds = (now - previous.at) / 1000;
        if (seconds > 0.1) {
          const requests = counterDelta(sample, previous.sample, 'requests');
          const errors = counterDelta(sample, previous.sample, 'failures4xx') +
            counterDelta(sample, previous.sample, 'failures5xx');
          const cpu = sample.cpuNanos >= 0 && previous.sample.cpuNanos >= 0
            ? Math.max(0, Math.min(100,
                (sample.cpuNanos - previous.sample.cpuNanos) /
                (seconds * 1e9 * Math.max(1, sample.processors)) * 100))
            : null;
          const point = {
            time: new Date().toLocaleTimeString(),
            requests: requests / seconds,
            errors: errors / seconds,
            inbound: counterDelta(sample, previous.sample, 'inboundBytes') / seconds / 1024,
            outbound: counterDelta(sample, previous.sample, 'outboundBytes') / seconds / 1024,
            cpu,
            memory: rss / 1048576,
            heap: sample.heapBytes / 1048576,
            seconds
          };
          history.push(point);
          if (history.length > 60) history.shift();
          show('perf-rps', rate(point.requests));
          show('perf-latency', requests ?
            (counterDelta(sample, previous.sample, 'durationNanos') / requests / 1e6).toFixed(1) + ' ms' : '—');
          show('perf-inbound', bytes(point.inbound * 1024, '/s'));
          show('perf-outbound', bytes(point.outbound * 1024, '/s'));
          const cpuPoints = history.filter(entry => entry.cpu !== null);
          const cpuSeconds = cpuPoints.reduce((total, entry) => total + entry.seconds, 0);
          show('perf-cpu', cpuSeconds ?
            (cpuPoints.reduce((total, entry) => total + entry.cpu * entry.seconds, 0) /
              cpuSeconds).toFixed(1) + '%' : '—');
          updateCharts();
        }
      }
      previous = { sample, at: now };
      state.textContent = history.length ? 'Live · recent samples' : 'Collecting a second sample…';
    } catch {
      state.textContent = 'Connection lost · retrying';
      previous = null;
    } finally {
      clearTimeout(timeout);
      polling = false;
    }
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      previous = null;
      state.textContent = 'Paused while this tab is hidden';
    } else {
      state.textContent = 'Collecting a fresh sample…';
      poll();
    }
  });
  poll();
  setInterval(poll, 3000);
})();
