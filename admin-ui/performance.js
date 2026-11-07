(() => {
  let cleanup = null;

  function mount() {
    if (cleanup) cleanup();
    const base = '/_objectstore/admin';
    const state = document.getElementById('perf-state');
    if (!state) return;
    const loader = document.getElementById('perf-loader');
    let active = true;
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
    let stream = null;
    let fallback = null;
    let polling = false;
    let memoryFallback = false;

    function show(id, value) {
      document.getElementById(id).textContent = value;
    }

    function showState(message, status) {
      state.textContent = message;
      state.dataset.state = status;
      if (loader) loader.hidden = status !== 'loading';
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

    function showMemory(sample) {
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
      return rss;
    }

    function cpuUsage(sample, before, seconds) {
      if (!(sample.cpuNanos >= 0 && before.cpuNanos >= 0)) return null;
      return Math.max(0, Math.min(100,
        (sample.cpuNanos - before.cpuNanos) /
        (seconds * 1e9 * Math.max(1, sample.processors)) * 100));
    }

    function samplePoint(sample, before, seconds, rss) {
      const errors = counterDelta(sample, before, 'failures4xx') +
        counterDelta(sample, before, 'failures5xx');
      return {
        time: new Date().toLocaleTimeString(),
        requests: counterDelta(sample, before, 'requests') / seconds,
        errors: errors / seconds,
        inbound: counterDelta(sample, before, 'inboundBytes') / seconds / 1024,
        outbound: counterDelta(sample, before, 'outboundBytes') / seconds / 1024,
        cpu: cpuUsage(sample, before, seconds),
        memory: rss / 1048576,
        heap: sample.heapBytes / 1048576,
        seconds
      };
    }

    function showCpuAverage() {
      const cpuPoints = history.filter(entry => entry.cpu !== null);
      const cpuSeconds = cpuPoints.reduce((total, entry) => total + entry.seconds, 0);
      show('perf-cpu', cpuSeconds ?
        (cpuPoints.reduce((total, entry) => total + entry.cpu * entry.seconds, 0) /
          cpuSeconds).toFixed(1) + '%' : '—');
    }

    function showInterval(sample, before, point) {
      const requests = counterDelta(sample, before, 'requests');
      show('perf-rps', rate(point.requests));
      show('perf-latency', requests ?
        (counterDelta(sample, before, 'durationNanos') / requests / 1e6).toFixed(1) + ' ms' : '—');
      show('perf-inbound', bytes(point.inbound * 1024, '/s'));
      show('perf-outbound', bytes(point.outbound * 1024, '/s'));
      showCpuAverage();
      updateCharts();
    }

    function recordSample(sample, now, rss) {
      if (!previous) return;
      const before = previous.sample;
      if (sample.requests < before.requests) {
        history.length = 0;
        updateCharts();
      }
      if (!(sample.requests >= before.requests)) return;
      const seconds = (now - previous.at) / 1000;
      if (!(seconds > 0.1)) return;
      const point = samplePoint(sample, before, seconds, rss);
      history.push(point);
      if (history.length > 60) history.shift();
      showInterval(sample, before, point);
    }

    function accept(sample) {
      if (!active || document.hidden) return;
      try {
        const now = performance.now();
        const rss = showMemory(sample);
        show('perf-gc', Number(sample.gcCount).toLocaleString());
        show('perf-inflight', Number(sample.inFlight).toLocaleString());
        recordSample(sample, now, rss);
        previous = { sample, at: now };
        showState(history.length ? 'Live · one-second samples' :
          'Collecting a second sample…', history.length ? 'live' : 'loading');
      } catch {
        showState('Invalid metrics sample · retrying', 'error');
        previous = null;
      }
    }

    async function pollFallback() {
      if (!active || document.hidden || polling) return;
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
        accept(await response.json());
      } catch {
        if (!active) return;
        showState('Connection lost · retrying', 'error');
        previous = null;
      } finally {
        clearTimeout(timeout);
        polling = false;
      }
    }

    function connect() {
      if (!active || document.hidden) return;
      if (!window.EventSource) {
        if (!fallback) fallback = setInterval(pollFallback, 1000);
        pollFallback();
        return;
      }
      stream = new EventSource(base + '/performance.events');
      stream.onmessage = event => {
        try { accept(JSON.parse(event.data)); }
        catch { showState('Invalid metrics sample · retrying', 'error'); }
      };
      stream.onerror = () => {
        if (!active) return;
        previous = null;
        showState('Connection lost · retrying', 'error');
        fetch(base + '/performance.json', { credentials: 'same-origin', cache: 'no-store' })
          .then(response => { if (response.redirected) location.assign(base + '/login'); })
          .catch(() => {});
      };
    }

    function disconnect() {
      stream?.close();
      stream = null;
      if (fallback) clearInterval(fallback);
      fallback = null;
      previous = null;
    }

    const visibilityChanged = () => {
      if (!active) return;
      if (document.hidden) {
        disconnect();
        showState('Paused while this tab is hidden', 'paused');
      } else {
        showState('Collecting a fresh sample…', 'loading');
        connect();
      }
    };
    document.addEventListener('visibilitychange', visibilityChanged);
    connect();
    cleanup = () => {
      active = false;
      disconnect();
      document.removeEventListener('visibilitychange', visibilityChanged);
    };
  }

  function unmount() {
    if (cleanup) cleanup();
    cleanup = null;
  }

  window.ObjectStorePerformance = { mount, unmount };
  if (document.readyState === 'loading')
    document.addEventListener('DOMContentLoaded', mount, { once: true });
  else mount();
})();
