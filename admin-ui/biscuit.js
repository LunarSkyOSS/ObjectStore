(function() {
	// \0rolldown/runtime.js
	var __defProp = Object.defineProperty;
	var __exportAll = (all, no_symbols) => {
		let target = {};
		for (var name in all) __defProp(target, name, {
			get: all[name],
			enumerable: true
		});
		if (!no_symbols) __defProp(target, Symbol.toStringTag, { value: "Module" });
		return target;
	};
	// src/extra/shared.js
	var HTMLElementBase = class extends (globalThis.HTMLElement ?? class {}) {
		get spacing() {
			return this.getAttribute("spacing") ?? "inherit";
		}
		set spacing(value) {
			if (value == null || value === "inherit") this.removeAttribute("spacing");
			else this.setAttribute("spacing", String(value));
		}
	};
	var baseStyles = `
  :host { box-sizing: border-box; color-scheme: inherit; color: var(--biscuit-color, light-dark(#24252b,#f3f4f8)); font: 400 1rem/1.5 system-ui,sans-serif; }
  :host([spacing="compact"]) { --biscuit-space-scale: 0.8; }
  :host([spacing="normal"]) { --biscuit-space-scale: 1; }
  :host([spacing="spacious"]) { --biscuit-space-scale: 1.3; }
  :host([theme="light"]) { color-scheme: light; --biscuit-focus-color: #6b63db; --biscuit-on-accent: #fff; }
  :host([theme="dark"]) { color-scheme: dark; --biscuit-focus-color: #aaa2ff; --biscuit-on-accent: #211b3c; }
  :host([theme="deep-dark"]) { color-scheme: dark; --biscuit-surface: #000; --biscuit-color: #fff; --biscuit-border-color: #fff; --biscuit-focus-color: #fff; --biscuit-on-accent: #000; --biscuit-switch-track: #000; --biscuit-switch-checked-track: #fff; --biscuit-switch-checked-thumb: #000; }
  :host([theme="auto"]) { color-scheme: light dark; --biscuit-focus-color: light-dark(#6b63db,#aaa2ff); --biscuit-on-accent: light-dark(#fff,#211b3c); }
  *, *::before, *::after { box-sizing: border-box; }
  button, input, select { font: inherit; }
  button:focus-visible, input:focus-visible, [tabindex]:focus-visible { outline: 2px solid var(--biscuit-focus-color,#786fc6); outline-offset: 2px; }
`;
	var surface = "var(--biscuit-surface,light-dark(#fff,#292c37))";
	var border = "var(--biscuit-border-color,light-dark(#c9ccd5,#626777))";
	function style(root, css) {
		const node = document.createElement("style");
		node.textContent = baseStyles + css;
		root.append(node);
	}
	function define(tag, constructor) {
		const registry = globalThis.customElements;
		if (!registry) return constructor;
		const prior = registry.get(tag);
		if (prior && prior !== constructor) throw new Error(`<${tag}> is already registered by another library`);
		if (!prior) registry.define(tag, constructor);
		return constructor;
	}
	function event(element, type, detail, cancelable = false) {
		return element.dispatchEvent(new CustomEvent(type, {
			detail,
			bubbles: true,
			composed: true,
			cancelable
		}));
	}
	function options(element, source, keys) {
		for (const key of keys) if (Object.hasOwn(source ?? {}, key)) element[key] = source[key];
		if (Object.hasOwn(source ?? {}, "spacing")) element.spacing = source.spacing;
		return element;
	}
	function themeProperty(constructor) {
		Object.defineProperty(constructor.prototype, "theme", {
			get() {
				return this.getAttribute("theme") ?? "inherit";
			},
			set(value) {
				if (value == null || value === "inherit") this.removeAttribute("theme");
				else this.setAttribute("theme", String(value));
			}
		});
	}
	function boolProperty(constructor, property, attribute = property) {
		Object.defineProperty(constructor.prototype, property, {
			get() {
				return this.hasAttribute(attribute);
			},
			set(value) {
				this.toggleAttribute(attribute, Boolean(value));
			}
		});
	}
	function stringProperty(constructor, property, attribute = property, fallback = "") {
		Object.defineProperty(constructor.prototype, property, {
			get() {
				return this.getAttribute(attribute) ?? fallback;
			},
			set(value) {
				if (value == null) this.removeAttribute(attribute);
				else this.setAttribute(attribute, String(value));
			}
		});
	}
	function syncFormValue(host, value) {
		host._internals?.setFormValue(value);
		if (!host._internals) {
			if (!host._fallback) {
				host._fallback = document.createElement("input");
				host._fallback.type = "hidden";
				host.append(host._fallback);
			}
			host._fallback.name = host.name ?? "";
			host._fallback.value = value ?? "";
			host._fallback.disabled = value == null || host.disabled || !host.name;
		}
	}
	function syncValidity(host, control) {
		if (!host._internals || !control?.validity) return;
		const validity = control.validity;
		if (validity.valid) host._internals.setValidity({});
		else {
			const flags = {};
			for (const key of [
				"valueMissing",
				"typeMismatch",
				"patternMismatch",
				"tooLong",
				"tooShort",
				"rangeUnderflow",
				"rangeOverflow",
				"stepMismatch",
				"badInput",
				"customError"
			]) if (validity[key]) flags[key] = true;
			host._internals.setValidity(flags, control.validationMessage, control);
		}
	}
	function positionPanel(panel, anchor, offset = 6) {
		if (!panel || !anchor) return;
		const box = anchor.getBoundingClientRect();
		const width = panel.offsetWidth || 240;
		const height = panel.offsetHeight || 100;
		let left = Math.min(Math.max(8, box.left), Math.max(8, innerWidth - width - 8));
		let top = box.bottom + offset;
		if (top + height > innerHeight - 8 && box.top - height - offset >= 8) top = box.top - height - offset;
		else top = Math.min(top, Math.max(8, innerHeight - height - 8));
		panel.style.left = `${left}px`;
		panel.style.top = `${top}px`;
	}
	// src/charts/calendar.js
	var DAY = 864e5;
	var weekdays = [
		"Sun",
		"Mon",
		"Tue",
		"Wed",
		"Thu",
		"Fri",
		"Sat"
	];
	var monthFormatter = new Intl.DateTimeFormat("en", {
		month: "short",
		timeZone: "UTC"
	});
	function dayStamp(value) {
		if (value == null || value === "") return null;
		if (typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value)) {
			const [year, month, day] = value.split("-").map(Number);
			const stamp = Date.UTC(year, month - 1, day);
			return iso$1(stamp) === value ? stamp : null;
		}
		const date = value instanceof Date ? value : new Date(typeof value === "string" && /^\d{4}-\d{2}-\d{2}$/.test(value) ? `${value}T00:00:00Z` : value);
		if (!Number.isFinite(date.getTime())) return null;
		const stamp = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), date.getUTCDate());
		return Number.isFinite(stamp) ? stamp : null;
	}
	var iso$1 = (stamp) => new Date(stamp).toISOString().slice(0, 10);
	var boundedYear = (value) => {
		const year = Number(value);
		return Number.isInteger(year) && year >= 1e3 && year <= 9999 ? year : null;
	};
	function calendarDomain(series, width, height, options = {}) {
		const points = series.flatMap((spec) => spec.points);
		const today = dayStamp(options.endDate) ?? dayStamp(/* @__PURE__ */ new Date());
		const firstDate = points.map((point) => dayStamp(point.x)).find((stamp) => stamp != null);
		const year = boundedYear(options.year) ?? new Date(firstDate ?? today).getUTCFullYear();
		const style = options.calendarStyle === "activity" ? "activity" : "calendar";
		const rolling = style === "activity" && options.calendarRange === "rolling";
		const month = Math.max(1, Math.min(12, Math.floor(Number(options.month) || new Date(firstDate ?? today).getUTCMonth() + 1)));
		const start = style === "calendar" ? Date.UTC(year, month - 1, 1) : rolling ? today - 364 * DAY : Date.UTC(year, 0, 1);
		const end = style === "calendar" ? Date.UTC(year, month, 1) - DAY : rolling ? today : Date.UTC(year + 1, 0, 1) - DAY;
		const weekStart = Math.max(0, Math.min(6, Math.floor(Number(options.weekStart) || 0)));
		const leading = (new Date(start).getUTCDay() - weekStart + 7) % 7;
		const weeks = Math.ceil((leading + (end - start) / DAY + 1) / 7);
		const left = style === "activity" ? 36 : 12, top = style === "activity" ? 28 : 42;
		const step = style === "activity" ? Math.max(14, Math.min(18, (Math.max(width, 760) - left - 20) / weeks)) : Math.max(48, (Math.max(width, 360) - left - 12) / 7);
		const size = style === "activity" ? step - 3 : step - 4;
		const rowHeight = style === "activity" ? step : Math.max(74, Number(options.dayHeight) || 92);
		const chartWidth = style === "activity" ? Math.max(width, Math.ceil(left + weeks * step + 20)) : Math.max(width, Math.ceil(left + 7 * step + 12));
		const chartHeight = style === "activity" ? Math.max(height, Math.ceil(top + 7 * step + 38)) : Math.max(height, Math.ceil(top + weeks * rowHeight + 12));
		const totals = /* @__PURE__ */ new Map();
		for (const point of points) {
			const stamp = dayStamp(point.x);
			if (stamp == null || stamp < start || stamp > end) continue;
			const value = Number(point.y);
			if (!Number.isFinite(value) || value < 0) continue;
			const current = totals.get(stamp);
			totals.set(stamp, {
				value: (current?.value ?? 0) + value,
				row: point.row
			});
		}
		const days = [];
		const months = [];
		let max = 0, total = 0, active = 0, previousMonth = -1;
		for (let stamp = start; stamp <= end; stamp += DAY) {
			const date = new Date(stamp), offset = (stamp - start) / DAY, week = Math.floor((offset + leading) / 7), weekday = (date.getUTCDay() - weekStart + 7) % 7;
			const entry = totals.get(stamp), value = entry?.value ?? 0;
			max = Math.max(max, value);
			total += value;
			if (value > 0) active++;
			days.push({
				date: iso$1(stamp),
				stamp,
				value,
				row: entry?.row ?? null,
				week,
				weekday
			});
			const month = date.getUTCMonth();
			if (month !== previousMonth) {
				months.push({
					label: monthFormatter.format(date),
					x: left + week * step
				});
				previousMonth = month;
			}
		}
		return {
			series,
			width: chartWidth,
			height: chartHeight,
			xKind: "calendar",
			yKind: "number",
			xDomain: [start, end],
			yDomain: [0, max],
			xScale: null,
			yScale: null,
			plot: {
				left,
				top,
				right: chartWidth - 12,
				bottom: chartHeight - 12
			},
			calendar: {
				days,
				months,
				weekdays,
				weekStart,
				step,
				size,
				rowHeight,
				left,
				top,
				weeks,
				year,
				month,
				rolling,
				total,
				active,
				max,
				style
			}
		};
	}
	function calendarGeometry(model) {
		const { days, left, top, step, size, rowHeight, max, style } = model.calendar;
		return days.map((day, index) => ({
			kind: "calendar",
			shape: "rect",
			series: 0,
			label: model.series[0]?.name || "Activity",
			point: {
				x: day.date,
				y: day.value,
				value: day.value,
				row: day.row ?? {
					date: day.date,
					value: day.value
				},
				index,
				series: 0
			},
			row: day.row,
			x: style === "activity" ? left + day.week * step : left + day.weekday * step,
			y: style === "activity" ? top + day.weekday * step : top + day.week * rowHeight,
			width: size,
			height: style === "activity" ? size : rowHeight - 4,
			level: day.value ? Math.max(1, Math.min(4, Math.ceil(day.value / Math.max(1, max) * 4))) : 0,
			date: day.date
		}));
	}
	function calendarSummary(model) {
		const { total, active, days, rolling, year, month, style } = model.calendar;
		return `${new Intl.NumberFormat().format(total)} activities across ${active} active days in ${style === "calendar" ? `${monthFormatter.format(new Date(Date.UTC(year, month - 1, 1)))} ${year}` : rolling ? "the last " + days.length + " days" : year}`;
	}
	function normalizeHex(value) {
		if (typeof value !== "string" || !/^#(?:[0-9a-f]{3}|[0-9a-f]{4}|[0-9a-f]{6}|[0-9a-f]{8})$/i.test(value)) return null;
		return value.length === 4 || value.length === 5 ? "#" + [...value.slice(1)].map((digit) => digit + digit).join("").toLowerCase() : value.toLowerCase();
	}
	function mixHex(from, to, amount) {
		const a = normalizeHex(from), b = normalizeHex(to);
		if (!a || !b) return null;
		const t = Math.max(0, Math.min(1, Number(amount) || 0));
		return "#" + (a.length === 9 || b.length === 9 ? [
			0,
			2,
			4,
			6
		] : [
			0,
			2,
			4
		]).map((index) => Math.round(parseInt(a.slice(index + 1, index + 3) || "ff", 16) * (1 - t) + parseInt(b.slice(index + 1, index + 3) || "ff", 16) * t).toString(16).padStart(2, "0")).join("");
	}
	function calendarPalette(theme = "light", mode = "color", style = "calendar", override = null, accent = null) {
		if (mode === "monochrome") return theme === "light" ? [
			"#ebedf0",
			"#c6c9ce",
			"#9b9fa6",
			"#686d75",
			"#333840"
		] : [
			"#161616",
			"#454545",
			"#777",
			"#b3b3b3",
			"#fff"
		];
		const base = normalizeHex(accent);
		if (Array.isArray(override) && override.length === 5 && override.every((color) => normalizeHex(color))) return override.map(normalizeHex);
		if (base) {
			const empty = theme === "light" ? "#ebedf0" : theme === "deep-dark" ? "#111111" : "#242a32", surface = theme === "light" ? "#ffffff" : theme === "deep-dark" ? "#000000" : "#242733";
			return [empty, ...[
				.25,
				.5,
				.75,
				1
			].map((weight) => mixHex(surface, base, weight))];
		}
		if (style === "activity") return theme === "light" ? [
			"#ebedf0",
			"#9be9a8",
			"#40c463",
			"#30a14e",
			"#216e39"
		] : theme === "deep-dark" ? [
			"#111",
			"#0e4429",
			"#006d32",
			"#26a641",
			"#39d353"
		] : [
			"#242a32",
			"#0e4429",
			"#006d32",
			"#26a641",
			"#39d353"
		];
		return theme === "light" ? [
			"#ececf3",
			"#ddd7fa",
			"#bcb0f3",
			"#9180e2",
			"#6352b8"
		] : theme === "deep-dark" ? [
			"#151515",
			"#3c345c",
			"#6654a1",
			"#9884ed",
			"#c8bcff"
		] : [
			"#2c2d3a",
			"#4b456c",
			"#6e5caa",
			"#9983e2",
			"#c8bcff"
		];
	}
	function calendarValueColor(value, max, palette, scale = "steps") {
		if (!(value > 0)) return palette[0];
		if (scale !== "continuous") return palette[Math.max(1, Math.min(4, Math.ceil(value / Math.max(1, max) * 4)))];
		const position = Math.max(0, Math.min(1, value / Math.max(1, max))) * 3;
		const low = Math.min(3, Math.floor(position));
		return mixHex(palette[low + 1], palette[low + 2] ?? palette[4], position - low);
	}
	function calendarAxesSvg(model, { theme = "light", colorMode = "color", calendarColors = null, calendarColor = null, colorScale = "steps", id = "chart" } = {}) {
		const info = model.calendar;
		if (!info) return "";
		const ink = theme === "light" ? "#687082" : theme === "deep-dark" ? "#eee" : "#b8bdcd";
		const palette = calendarPalette(theme, colorMode, info.style, calendarColors, calendarColor);
		let out = "";
		if (info.style === "calendar") {
			const title = new Intl.DateTimeFormat("en", {
				month: "long",
				year: "numeric",
				timeZone: "UTC"
			}).format(new Date(Date.UTC(info.year, info.month - 1, 1)));
			out += `<text x="${info.left}" y="19" fill="${ink}" font-size="16" font-weight="700">${title}</text>`;
			for (let index = 0; index < 7; index++) out += `<text x="${(info.left + index * info.step + 8).toFixed(2)}" y="35" fill="${ink}" font-size="11">${info.weekdays[(info.weekStart + index) % 7]}</text>`;
			return out;
		}
		for (const month of info.months) out += `<text x="${month.x.toFixed(2)}" y="15" fill="${ink}" font-size="11">${month.label}</text>`;
		for (const index of [
			1,
			3,
			5
		]) {
			const day = info.weekdays[(info.weekStart + index) % 7];
			out += `<text x="${info.left - 7}" y="${(info.top + index * info.step + info.size * .75).toFixed(2)}" text-anchor="end" fill="${ink}" font-size="10">${day}</text>`;
		}
		const y = info.top + 7 * info.step + 19, start = Math.max(info.left, model.width - 137);
		out += `<text x="${start - 6}" y="${y + 9}" text-anchor="end" fill="${ink}" font-size="10">Less</text>`;
		if (colorScale === "continuous") out += `<rect x="${start}" y="${y}" width="76" height="12" rx="2" fill="url(#${id}-calendar-scale)"/>`;
		else palette.forEach((color, index) => {
			out += `<rect x="${start + index * 16}" y="${y}" width="12" height="12" rx="2" fill="${color}"/>`;
		});
		out += `<text x="${start + 86}" y="${y + 9}" fill="${ink}" font-size="10">More</text>`;
		return out;
	}
	// src/groups/calendar.js
	var iso = (stamp) => new Date(stamp).toISOString().slice(0, 10);
	var dateValue = (value) => {
		const stamp = dayStamp(value);
		return stamp == null ? "" : iso(stamp);
	};
	var days = [
		"Sun",
		"Mon",
		"Tue",
		"Wed",
		"Thu",
		"Fri",
		"Sat"
	];
	var css$1 = `${baseStyles}
:host{display:block;min-width:0;--_surface:var(--biscuit-surface,light-dark(#fff,#292c37));--_ink:var(--biscuit-color,light-dark(#24252b,#f3f4f8));--_line:var(--biscuit-border-color,light-dark(#d6d8df,#626777));--_accent:var(--biscuit-focus-color,light-dark(#6b63db,#aaa2ff));color:var(--_ink)}
:host([theme="deep-dark"]){--_surface:#000;--_ink:#fff;--_line:#fff;--_accent:#fff}
.calendar{background:var(--_surface);border:1px solid var(--_line);border-radius:12px;padding:calc(12px * var(--biscuit-space-scale,1));max-width:100%}.head{display:flex;justify-content:space-between;align-items:center;gap:8px;margin-bottom:10px}.head strong{font-size:1.05rem}.head button,.day{color:inherit;background:transparent;border:0;cursor:pointer}.head button{width:34px;height:34px;border:1px solid var(--_line);border-radius:7px}.grid{display:grid;grid-template-columns:repeat(7,minmax(0,1fr));gap:3px}.weekday{text-align:center;color:color-mix(in srgb,var(--_ink) 65%,transparent);font-size:.75rem;padding:4px}.cell{min-width:0;min-height:var(--biscuit-calendar-day-height,72px);border:1px solid var(--_line);border-radius:6px;overflow:hidden;padding:3px}.cell.outside{visibility:hidden}.day{display:grid;place-items:center;width:28px;height:28px;border-radius:50%;font-size:.82rem}.day:hover{background:color-mix(in srgb,var(--_accent) 14%,transparent)}.day[aria-selected="true"]{background:var(--_accent);color:var(--biscuit-on-accent,#fff)}.day[aria-current="date"]{outline:1px solid var(--_accent)}.day:disabled{opacity:.35;cursor:default}.content{min-width:0;font-size:.72rem;overflow:auto;max-height:calc(var(--biscuit-calendar-day-height,72px) - 35px)}.content slot::slotted(*){max-width:100%}button:focus-visible{outline:2px solid var(--_accent);outline-offset:2px}@media(max-width:520px){.calendar{padding:6px}.grid{gap:1px}.cell{min-height:var(--biscuit-calendar-day-height,54px);padding:1px}.day{width:24px;height:24px}.content{max-height:25px}}
`;
	var BiscuitCalendarView = class extends HTMLElementBase {
		static observedAttributes = [
			"year",
			"month",
			"value",
			"week-start",
			"min",
			"max",
			"theme",
			"spacing"
		];
		constructor() {
			super();
			if (this.attachShadow) {
				this._root = this.attachShadow({ mode: "open" });
				this._root.innerHTML = `<style>${css$1}</style><div class="calendar" part="calendar"><div class="head" part="header"><button class="previous" type="button" aria-label="Previous month">‹</button><strong class="title"></strong><button class="next" type="button" aria-label="Next month">›</button></div><div class="grid" part="grid"></div></div>`;
				this._root.querySelector(".previous").addEventListener("click", () => this.navigate(-1));
				this._root.querySelector(".next").addEventListener("click", () => this.navigate(1));
				this._root.querySelector(".grid").addEventListener("click", (e) => {
					const button = e.target.closest("button[data-date]");
					if (button && !button.disabled) this._choose(button.dataset.date);
				});
				this._root.querySelector(".grid").addEventListener("keydown", (e) => this._onKey(e));
			}
		}
		connectedCallback() {
			this._observer = typeof MutationObserver !== "undefined" ? new MutationObserver(() => this._render()) : null;
			this._observer?.observe(this, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: ["date"]
			});
			this._render();
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		attributeChangedCallback() {
			this._render();
		}
		get year() {
			return Number(this.getAttribute("year")) || (/* @__PURE__ */ new Date()).getFullYear();
		}
		set year(value) {
			this.setAttribute("year", String(value));
		}
		get month() {
			return Number(this.getAttribute("month")) || (/* @__PURE__ */ new Date()).getMonth() + 1;
		}
		set month(value) {
			this.setAttribute("month", String(value));
		}
		get weekStart() {
			return Number(this.getAttribute("week-start")) || 0;
		}
		set weekStart(value) {
			this.setAttribute("week-start", String(value));
		}
		get value() {
			return this.getAttribute("value") || "";
		}
		set value(value) {
			const normalized = dateValue(value);
			if (normalized) this.setAttribute("value", normalized);
			else this.removeAttribute("value");
		}
		get min() {
			return this.getAttribute("min") || "";
		}
		set min(value) {
			if (value == null || value === "") this.removeAttribute("min");
			else this.setAttribute("min", dateValue(value));
		}
		get max() {
			return this.getAttribute("max") || "";
		}
		set max(value) {
			if (value == null || value === "") this.removeAttribute("max");
			else this.setAttribute("max", dateValue(value));
		}
		navigate(offset) {
			const date = new Date(Date.UTC(this.year, this.month - 1 + offset, 1));
			this.year = date.getUTCFullYear();
			this.month = date.getUTCMonth() + 1;
			event(this, "monthchange", {
				year: this.year,
				month: this.month
			});
		}
		setDayContent(date, content) {
			const key = dateValue(date);
			if (!key) throw new TypeError("Expected a valid date");
			let day = [...this.querySelectorAll(":scope > biscuit-calendar-day")].find((node) => node.getAttribute("date") === key);
			if (!day) {
				day = document.createElement("biscuit-calendar-day");
				day.setAttribute("date", key);
				this.append(day);
			}
			day.replaceChildren(...(Array.isArray(content) ? content : [content]).filter(Boolean).map((item) => typeof item === "string" ? document.createTextNode(item) : item));
			this._render();
			return day;
		}
		_choose(date) {
			const previous = this.value;
			this.value = date;
			event(this, "input", {
				value: date,
				date
			});
			if (previous !== date) event(this, "change", {
				value: date,
				date
			});
		}
		_onKey(e) {
			const focused = this._root.activeElement?.dataset?.date;
			if (!focused) return;
			const delta = {
				ArrowLeft: -1,
				ArrowRight: 1,
				ArrowUp: -7,
				ArrowDown: 7
			}[e.key];
			if (delta === void 0 && e.key !== "Home" && e.key !== "End") return;
			e.preventDefault();
			const target = /* @__PURE__ */ new Date(`${focused}T00:00:00Z`);
			if (delta !== void 0) target.setUTCDate(target.getUTCDate() + delta);
			else target.setUTCDate(e.key === "Home" ? 1 : new Date(Date.UTC(target.getUTCFullYear(), target.getUTCMonth() + 1, 0)).getUTCDate());
			if (target.getUTCMonth() + 1 !== this.month || target.getUTCFullYear() !== this.year) {
				this.year = target.getUTCFullYear();
				this.month = target.getUTCMonth() + 1;
			}
			this._root.querySelector(`button[data-date="${iso(target.getTime())}"]`)?.focus();
		}
		_render() {
			if (!this._root) return;
			let year = this.year, month = this.month;
			if (!Number.isInteger(year) || year < 1e3 || year > 9999) year = (/* @__PURE__ */ new Date()).getFullYear();
			if (!Number.isInteger(month) || month < 1 || month > 12) month = 1;
			const first = Date.UTC(year, month - 1, 1), count = new Date(Date.UTC(year, month, 0)).getUTCDate(), leading = (new Date(first).getUTCDay() - this.weekStart + 7) % 7;
			this._root.querySelector(".title").textContent = new Intl.DateTimeFormat(void 0, {
				month: "long",
				year: "numeric",
				timeZone: "UTC"
			}).format(new Date(first));
			const grid = this._root.querySelector(".grid");
			grid.replaceChildren();
			for (let i = 0; i < 7; i++) {
				const label = document.createElement("span");
				label.className = "weekday";
				label.textContent = days[(i + this.weekStart) % 7];
				grid.append(label);
			}
			const today = dateValue(/* @__PURE__ */ new Date()), min = this.getAttribute("min"), max = this.getAttribute("max");
			for (let i = 0; i < Math.ceil((leading + count) / 7) * 7; i++) {
				const number = i - leading + 1, cell = document.createElement("div");
				cell.className = "cell";
				cell.setAttribute("part", "day");
				if (number < 1 || number > count) {
					cell.classList.add("outside");
					grid.append(cell);
					continue;
				}
				const date = iso(Date.UTC(year, month - 1, number)), button = document.createElement("button");
				button.className = "day";
				button.type = "button";
				button.dataset.date = date;
				button.textContent = String(number);
				button.setAttribute("aria-label", new Intl.DateTimeFormat(void 0, {
					dateStyle: "full",
					timeZone: "UTC"
				}).format(/* @__PURE__ */ new Date(`${date}T00:00:00Z`)));
				button.setAttribute("aria-pressed", String(this.value === date));
				if (today === date) button.setAttribute("aria-current", "date");
				button.disabled = Boolean(min && date < min || max && date > max);
				cell.append(button);
				const matching = [...this.querySelectorAll(":scope > biscuit-calendar-day[date]")].find((day) => day.getAttribute("date") === date);
				if (matching) {
					const slotName = `day-${date}`;
					if (matching.slot !== slotName) matching.slot = slotName;
					const content = document.createElement("div");
					content.className = "content";
					const slot = document.createElement("slot");
					slot.name = slotName;
					content.append(slot);
					cell.append(content);
				}
				grid.append(cell);
			}
		}
	};
	themeProperty(BiscuitCalendarView);
	var pickerCss = `${baseStyles}:host{display:inline-block;position:relative;min-width:13rem;color:var(--biscuit-color,light-dark(#24252b,#f3f4f8))}:host([theme="deep-dark"]){--biscuit-surface:#000;--biscuit-color:#fff;--biscuit-border-color:#fff}label{display:block;margin-bottom:5px;font-weight:600}.row{display:flex;align-items:center;border:1px solid var(--biscuit-border-color,light-dark(#a5a8b3,#656b7a));border-radius:8px;background:var(--biscuit-surface,light-dark(#fff,#292c37));overflow:hidden}.row input{width:100%;min-width:0;border:0;padding:9px;color:inherit;background:transparent}.row button{border:0;border-left:1px solid var(--biscuit-border-color,light-dark(#a5a8b3,#656b7a));padding:8px 12px;color:inherit;background:transparent;cursor:pointer}.popup{position:absolute;z-index:30;top:calc(100% + 5px);left:0;width:min(360px,95vw);box-shadow:0 12px 32px #0003}.popup[hidden]{display:none}@media(max-width:420px){.popup{left:auto;right:0}}`;
	var BiscuitDatePickerView = class extends HTMLElementBase {
		static formAssociated = true;
		static observedAttributes = [
			"value",
			"name",
			"label",
			"min",
			"max",
			"disabled",
			"required",
			"theme",
			"spacing"
		];
		constructor() {
			super();
			this._internals = this.attachInternals?.() ?? null;
			if (!this._internals?.setFormValue) this._internals = null;
			if (this.attachShadow) {
				this._root = this.attachShadow({ mode: "open" });
				this._root.innerHTML = `<style>${pickerCss}</style><label part="label"></label><div class="row" part="field"><input type="text" readonly><button type="button" aria-label="Open calendar" aria-expanded="false">▦</button></div><div class="popup" part="popup" role="dialog" aria-label="Choose a date" hidden><biscuit-calendarview></biscuit-calendarview></div>`;
				this._input = this._root.querySelector("input");
				this._button = this._root.querySelector("button");
				this._popup = this._root.querySelector(".popup");
				this._calendar = this._root.querySelector("biscuit-calendarview");
				this._button.addEventListener("click", (e) => {
					e.stopPropagation();
					this.open ? this.hide() : this.show();
				});
				this._calendar.addEventListener("change", (e) => {
					e.stopPropagation();
					this.value = e.detail.value;
					event(this, "input", { value: this.value });
					event(this, "change", { value: this.value });
					this.hide();
					this._button.focus();
				});
				this._root.addEventListener("keydown", (e) => {
					if (e.key === "Escape" && this.open) {
						e.preventDefault();
						this.hide();
						this._button.focus();
					}
				});
				this._outside = (e) => {
					if (!e.composedPath().includes(this)) this.hide();
				};
			}
		}
		connectedCallback() {
			this._defaultValue ??= this.value;
			this._render();
		}
		disconnectedCallback() {
			document.removeEventListener("pointerdown", this._outside);
		}
		attributeChangedCallback() {
			this._render();
		}
		get name() {
			return this.getAttribute("name") || "";
		}
		set name(v) {
			this.setAttribute("name", String(v));
		}
		get value() {
			return this.getAttribute("value") || "";
		}
		set value(v) {
			const normalized = dateValue(v);
			if (normalized) this.setAttribute("value", normalized);
			else this.removeAttribute("value");
		}
		get label() {
			return this.getAttribute("label") || "";
		}
		set label(v) {
			this.setAttribute("label", String(v));
		}
		get min() {
			return this.getAttribute("min") || "";
		}
		set min(v) {
			if (v == null || v === "") this.removeAttribute("min");
			else this.setAttribute("min", dateValue(v));
		}
		get max() {
			return this.getAttribute("max") || "";
		}
		set max(v) {
			if (v == null || v === "") this.removeAttribute("max");
			else this.setAttribute("max", dateValue(v));
		}
		get required() {
			return this.hasAttribute("required");
		}
		set required(v) {
			this.toggleAttribute("required", Boolean(v));
		}
		get disabled() {
			return this.hasAttribute("disabled");
		}
		set disabled(v) {
			this.toggleAttribute("disabled", Boolean(v));
		}
		get open() {
			return !this._popup.hidden;
		}
		show() {
			if (this.hasAttribute("disabled")) return;
			this._calendar.year = Number(this.value.slice(0, 4)) || (/* @__PURE__ */ new Date()).getFullYear();
			this._calendar.month = Number(this.value.slice(5, 7)) || (/* @__PURE__ */ new Date()).getMonth() + 1;
			this._popup.hidden = false;
			this._button.setAttribute("aria-expanded", "true");
			setTimeout(() => {
				if (this.open && this.isConnected) document.addEventListener("pointerdown", this._outside);
			}, 0);
			this._calendar.shadowRoot.querySelector(".day:not(:disabled)")?.focus();
		}
		hide() {
			this._popup.hidden = true;
			this._button.setAttribute("aria-expanded", "false");
			document.removeEventListener("pointerdown", this._outside);
		}
		formResetCallback() {
			this.value = this._defaultValue ?? "";
		}
		checkValidity() {
			return (!this.hasAttribute("required") || Boolean(this.value)) && (!this.value || !this.getAttribute("min") || this.value >= this.getAttribute("min")) && (!this.value || !this.getAttribute("max") || this.value <= this.getAttribute("max"));
		}
		reportValidity() {
			if (this.checkValidity()) return true;
			this.show();
			return false;
		}
		_render() {
			if (!this._root) return;
			this._root.querySelector("label").textContent = this.getAttribute("label") || "";
			this._root.querySelector("label").hidden = !this.hasAttribute("label");
			this._input.value = this.value;
			this._input.setAttribute("aria-label", this.getAttribute("label") || "Date");
			this._button.disabled = this.hasAttribute("disabled");
			this._calendar.value = this.value;
			for (const name of [
				"min",
				"max",
				"theme",
				"spacing"
			]) {
				const value = this.getAttribute(name);
				if (value == null) this._calendar.removeAttribute(name);
				else this._calendar.setAttribute(name, value);
			}
			syncFormValue(this, this.hasAttribute("disabled") ? null : this.value || null);
			const invalid = this.value && this.getAttribute("min") && this.value < this.getAttribute("min") ? { rangeUnderflow: true } : this.value && this.getAttribute("max") && this.value > this.getAttribute("max") ? { rangeOverflow: true } : { valueMissing: true };
			this._internals?.setValidity(this.checkValidity() ? {} : invalid, this.checkValidity() ? "" : "Choose a date within the allowed range.", this._input);
		}
	};
	themeProperty(BiscuitDatePickerView);
	function defineCalendarView() {
		return define("biscuit-calendarview", BiscuitCalendarView);
	}
	function defineDatePickerView() {
		defineCalendarView();
		return define("biscuit-datepickerview", BiscuitDatePickerView);
	}
	function createCalendarView(options = {}) {
		defineCalendarView();
		const view = document.createElement("biscuit-calendarview");
		for (const key of [
			"theme",
			"spacing",
			"year",
			"month",
			"weekStart",
			"value"
		]) if (Object.hasOwn(options, key)) view[key] = options[key];
		for (const key of ["min", "max"]) if (Object.hasOwn(options, key)) view.setAttribute(key, String(options[key]));
		return view;
	}
	function createDatePickerView(options = {}) {
		defineDatePickerView();
		const view = document.createElement("biscuit-datepickerview");
		for (const key of [
			"theme",
			"spacing",
			"name",
			"value"
		]) if (Object.hasOwn(options, key)) view[key] = options[key];
		for (const key of [
			"label",
			"min",
			"max",
			"required",
			"disabled"
		]) if (Object.hasOwn(options, key)) {
			if (typeof options[key] === "boolean") view.toggleAttribute(key, options[key]);
			else view.setAttribute(key, String(options[key]));
		}
		return view;
	}
	// src/groups/shared.js
	var themeStyles = `
  :host([spacing="compact"]) { --biscuit-space-scale: 0.8; }
  :host([spacing="normal"]) { --biscuit-space-scale: 1; }
  :host([spacing="spacious"]) { --biscuit-space-scale: 1.3; }
  :host([theme]) {
    --biscuit-on-accent: light-dark(#fff, #211b3c);
    --biscuit-theme-surface: light-dark(#fff, #292c37);
    --biscuit-theme-color: light-dark(#24252b, #f3f4f8);
    --biscuit-theme-textview-border-color: light-dark(#a5a8b3, #656b7a);
    --biscuit-theme-cardview-border-color: light-dark(#d6d8df, #656b7a);
    --biscuit-theme-focus-color: light-dark(#6b63db, #aaa2ff);
    --biscuit-theme-cardview-shadow: light-dark(0 2px 8px rgb(36 37 43 / 8%), 0 3px 14px rgb(0 0 0 / 25%));
    --biscuit-theme-option-background: light-dark(#efedff, #45415f);
    --biscuit-theme-option-color: light-dark(#38306e, #fff);
    --biscuit-theme-button-primary-background: light-dark(#6256ad, #c0bbff);
    --biscuit-theme-button-primary-color: light-dark(#fff, #211b3c);
    --biscuit-theme-button-danger-background: light-dark(#be3e4a, #ff8d94);
    --biscuit-theme-button-danger-color: light-dark(#fff, #281215);
    --biscuit-theme-badge-neutral: light-dark(#555b69, #d2d5df);
    --biscuit-theme-badge-accent: light-dark(#5b4bb1, #c3baff);
    --biscuit-theme-badge-success: light-dark(#176d4b, #8ce0ae);
    --biscuit-theme-badge-warning: light-dark(#8b5600, #ffd27a);
    --biscuit-theme-badge-danger: light-dark(#ad3442, #ff9ca4);
    --biscuit-theme-badge-background: color-mix(in srgb, var(--_badge-tone) 12%, var(--biscuit-theme-surface));
    --biscuit-theme-badge-border: 1px solid color-mix(in srgb, var(--_badge-tone) 30%, transparent);
  }

  :host([theme="light"]) { color-scheme: light; }
  :host([theme="dark"]) { color-scheme: dark; }
  :host([theme="auto"]) { color-scheme: light dark; }

  :host([theme="deep-dark"]) {
    color-scheme: dark;
    --biscuit-on-accent: #000;
    --biscuit-switch-track: #000;
    --biscuit-switch-checked-track: #fff;
    --biscuit-switch-checked-thumb: #000;
    --biscuit-theme-surface: #000;
    --biscuit-theme-color: #fff;
    --biscuit-theme-textview-border-color: #fff;
    --biscuit-theme-cardview-border-color: #fff;
    --biscuit-theme-focus-color: #fff;
    --biscuit-theme-cardview-shadow: none;
    --biscuit-theme-option-background: #fff;
    --biscuit-theme-option-color: #000;
    --biscuit-theme-button-primary-background: #fff;
    --biscuit-theme-button-primary-color: #000;
    --biscuit-theme-button-danger-background: #fff;
    --biscuit-theme-button-danger-color: #000;
    --biscuit-theme-badge-neutral: #fff;
    --biscuit-theme-badge-accent: #fff;
    --biscuit-theme-badge-success: #fff;
    --biscuit-theme-badge-warning: #fff;
    --biscuit-theme-badge-danger: #fff;
    --biscuit-theme-badge-background: #000;
    --biscuit-theme-badge-border: 1px solid #fff;
  }
`;
	function createConfiguredElement(tag, options) {
		const element = document.createElement(tag);
		if (Object.hasOwn(options, "spacing")) element.spacing = options.spacing;
		return element;
	}
	// src/icon-data.js
	var iconSvgs = Object.freeze({
		"file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><path d=\"M8 12h7M8 15.5h7\"/></svg>",
		"folder": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M2.75 7.25A2.25 2.25 0 0 1 5 5h4.2l1.8 2h8A2.25 2.25 0 0 1 21.25 9.25v9A2.75 2.75 0 0 1 18.5 21h-13a2.75 2.75 0 0 1-2.75-2.75Z\"/><path d=\"M2.75 9h18.5\"/></svg>",
		"html-file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><path d=\"m10 11.5-2 2 2 2m4-4 2 2-2 2\"/></svg>",
		"js-file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><text x=\"12\" y=\"16.2\" text-anchor=\"middle\" font-family=\"system-ui,sans-serif\" font-size=\"6.2\" font-weight=\"800\" stroke=\"none\" fill=\"currentColor\">JS</text></svg>",
		"css-file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><path d=\"M10 11c-1.4 0-1.4 1.2-1.4 2.4S8.6 16 10 16m4-5c1.4 0 1.4 1.2 1.4 2.4S15.4 16 14 16\"/></svg>",
		"image-file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><circle cx=\"10\" cy=\"11\" r=\"1\"/><path d=\"m7.5 17 3.2-3.4 1.8 1.8 1.4-1.3 2.1 2.9\"/></svg>",
		"code-file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><path d=\"m10 11-2 2.5 2 2.5m4-5 2 2.5-2 2.5m-.5-5.5-2 6\"/></svg>",
		"svg-file": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M6.5 2.75H15L19.25 7v12.5a1.75 1.75 0 0 1-1.75 1.75h-11a1.75 1.75 0 0 1-1.75-1.75v-15A1.75 1.75 0 0 1 6.5 2.75Z\"/><path d=\"M15 2.75V7h4.25\"/><circle cx=\"8.5\" cy=\"15.5\" r=\"1\"/><circle cx=\"12\" cy=\"10.5\" r=\"1\"/><circle cx=\"15.5\" cy=\"15.5\" r=\"1\"/><path d=\"m9.2 14.7 2.2-3.3m1.2 0 2.2 3.3\"/></svg>",
		"sun": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"4\"/><path d=\"M12 2v2m0 16v2M4.93 4.93l1.42 1.42m11.3 11.3 1.42 1.42M2 12h2m16 0h2M4.93 19.07l1.42-1.42m11.3-11.3 1.42-1.42\"/></svg>",
		"moon": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M20.4 15.3A8.7 8.7 0 0 1 8.7 3.6 8.7 8.7 0 1 0 20.4 15.3Z\"/></svg>",
		"system": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><rect x=\"3\" y=\"4\" width=\"18\" height=\"13\" rx=\"2\"/><path d=\"M8 21h8m-4-4v4\"/><path d=\"M7 8h10\"/></svg>",
		"deep-dark": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"12\" cy=\"12\" r=\"9\"/><path d=\"M12 3a9 9 0 0 1 0 18Z\" fill=\"currentColor\" stroke=\"none\"/></svg>",
		"spacing-compact": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M8 7h12M8 11h12M8 15h12\"/><circle cx=\"4\" cy=\"7\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"4\" cy=\"11\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"4\" cy=\"15\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/></svg>",
		"spacing-normal": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M8 5h12M8 11h12M8 17h12\"/><circle cx=\"4\" cy=\"5\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"4\" cy=\"11\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"4\" cy=\"17\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/></svg>",
		"spacing-spacious": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M8 3h12M8 11h12M8 19h12\"/><circle cx=\"4\" cy=\"3\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"4\" cy=\"11\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/><circle cx=\"4\" cy=\"19\" r=\"1\" fill=\"currentColor\" stroke=\"none\"/></svg>",
		"x": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M5 5 19 19M19 5 5 19\"/></svg>",
		"check": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m4 12 5 5L20 6\"/></svg>",
		"chevron-right": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m9 5 7 7-7 7\"/></svg>",
		"chevron-down": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"m5 9 7 7 7-7\"/></svg>",
		"search": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"10.75\" cy=\"10.75\" r=\"6.75\"/><path d=\"m16 16 5 5\"/></svg>",
		"plus": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 4v16M4 12h16\"/></svg>",
		"refresh": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M20 10a8 8 0 0 0-14.4-4.4L3 8\"/><path d=\"M3 3v5h5\"/><path d=\"M4 14a8 8 0 0 0 14.4 4.4L21 16\"/><path d=\"M21 21v-5h-5\"/></svg>",
		"upload": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 16V3m-5 5 5-5 5 5\"/><path d=\"M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3\"/></svg>",
		"download": "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 24 24\" width=\"1em\" height=\"1em\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"1.75\" stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M12 3v13m-5-5 5 5 5-5\"/><path d=\"M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3\"/></svg>"
	});
	// src/groups/icons.js
	var iconNames = Object.freeze(Object.keys(iconSvgs));
	function getIconSvg(name) {
		const svg = iconSvgs[String(name)];
		if (!svg) throw new RangeError(`Unknown Biscuit icon: ${name}`);
		return svg;
	}
	function createIcon(name, { label } = {}) {
		if (!globalThis.document) throw new Error("Biscuit icons require a browser document");
		const element = document.createElement("span");
		element.className = "biscuit-icon";
		element.style.display = "inline-flex";
		element.style.alignItems = "center";
		element.style.justifyContent = "center";
		element.style.flex = "none";
		element.style.width = "1em";
		element.style.height = "1em";
		element.innerHTML = getIconSvg(name);
		if (label) {
			element.setAttribute("role", "img");
			element.setAttribute("aria-label", String(label));
		} else element.setAttribute("aria-hidden", "true");
		return element;
	}
	// src/groups/core.js
	var core_exports = /* @__PURE__ */ __exportAll({
		BiscuitBadgeView: () => BiscuitBadgeView,
		BiscuitButtonView: () => BiscuitButtonView,
		BiscuitCalendarView: () => BiscuitCalendarView,
		BiscuitCardView: () => BiscuitCardView,
		BiscuitDatePickerView: () => BiscuitDatePickerView,
		BiscuitDropdownView: () => BiscuitDropdownView,
		BiscuitLoadingIndicator: () => BiscuitLoadingIndicator,
		BiscuitPageLoaderView: () => BiscuitPageLoaderView,
		BiscuitTextView: () => BiscuitTextView,
		createBadgeView: () => createBadgeView,
		createButtonView: () => createButtonView,
		createCalendarView: () => createCalendarView,
		createCardView: () => createCardView,
		createDatePickerView: () => createDatePickerView,
		createDropdownView: () => createDropdownView,
		createLoadingIndicator: () => createLoadingIndicator,
		createPageLoaderView: () => createPageLoaderView,
		createTextView: () => createTextView,
		defineBadgeView: () => defineBadgeView,
		defineButtonView: () => defineButtonView,
		defineCalendarView: () => defineCalendarView,
		defineCardView: () => defineCardView,
		defineCore: () => defineCore,
		defineDatePickerView: () => defineDatePickerView,
		defineDropdownView: () => defineDropdownView,
		defineLoadingIndicator: () => defineLoadingIndicator,
		definePageLoaderView: () => definePageLoaderView,
		defineTextView: () => defineTextView
	});
	var TEXTVIEW_TAG_NAME = "biscuit-textview";
	var CARDVIEW_TAG_NAME = "biscuit-cardview";
	var DROPDOWNVIEW_TAG_NAME = "biscuit-dropdownview";
	var BUTTONVIEW_TAG_NAME = "biscuit-buttonview";
	var LOADINGINDICATOR_TAG_NAME = "biscuit-loadingindicator";
	var PAGELOADERVIEW_TAG_NAME = "biscuit-pageloaderview";
	var BADGEVIEW_TAG_NAME = "biscuit-badgeview";
	var dropdownListId = 0;
	var pageLoaderInertLocks = /* @__PURE__ */ new WeakMap();
	var pageLoaderPositionLocks = /* @__PURE__ */ new WeakMap();
	var textViewStyles = `
  :host {
    box-sizing: border-box;
    display: inline-block;
    color-scheme: inherit;
    min-width: var(--biscuit-textview-min-width, 12rem);
    padding: var(--biscuit-textview-padding, calc(0.5rem * var(--biscuit-space-scale, 1)) calc(0.625rem * var(--biscuit-space-scale, 1)));
    border: var(--biscuit-textview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-textview-border-color, light-dark(#a5a8b3, #656b7a))));
    border-radius: var(--biscuit-textview-radius, 0.375rem);
    background: var(--biscuit-textview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-textview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-textview-font, 400 1rem/1.5 system-ui, sans-serif);
    vertical-align: middle;
    resize: none;
  }

  ${themeStyles}

  :host([resizable]) {
    min-height: 3rem;
    overflow: auto;
    resize: both;
  }

  :host([resizable][limit-resize-to-parent]) {
    max-width: var(--biscuit-textview-parent-max-width, 100%);
    max-height: var(--biscuit-textview-parent-max-height, 100%);
    min-width: min(var(--biscuit-textview-min-width, 12rem), var(--biscuit-textview-parent-max-width, 100%));
    min-height: min(3rem, var(--biscuit-textview-parent-max-height, 100%));
  }

  :host([editable]:focus-within) {
    outline: 2px solid var(--biscuit-textview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: 2px;
  }

  [hidden] {
    display: none !important;
  }

  [part="text"] {
    display: block;
    min-height: 1.5em;
    white-space: pre-wrap;
    overflow-wrap: anywhere;
  }

  [part="editor"] {
    box-sizing: border-box;
    display: block;
    width: 100%;
    height: 100%;
    min-height: 1.5em;
    padding: 0;
    border: 0;
    outline: 0;
    resize: none;
    color: inherit;
    background: transparent;
    font: inherit;
  }
`;
	var cardViewStyles = `
  :host {
    box-sizing: border-box;
    display: block;
    color-scheme: inherit;
    padding: var(--biscuit-cardview-padding, calc(1.25rem * var(--biscuit-space-scale, 1)));
    border: var(--biscuit-cardview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-cardview-radius, 0.75rem);
    background: var(--biscuit-cardview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    box-shadow: var(--biscuit-cardview-shadow, var(--biscuit-theme-cardview-shadow, light-dark(0 2px 8px rgb(36 37 43 / 8%), 0 3px 14px rgb(0 0 0 / 25%))));
    color: var(--biscuit-cardview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-cardview-font, inherit);
  }

  ${themeStyles}

  [hidden] {
    display: none !important;
  }

  [part="heading"] {
    margin: 0 0 var(--biscuit-cardview-gap, calc(0.75rem * var(--biscuit-space-scale, 1)));
    font-size: var(--biscuit-cardview-heading-size, 1.125rem);
    line-height: 1.3;
  }

  [part="body"] {
    min-width: 0;
  }
`;
	var dropdownViewStyles = `
  :host {
    box-sizing: border-box;
    display: inline-block;
    position: relative;
    min-width: var(--biscuit-dropdownview-min-width, 12rem);
    color-scheme: inherit;
    color: var(--biscuit-dropdownview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-dropdownview-font, 400 1rem/1.5 system-ui, sans-serif);
  }

  ${themeStyles}

  :host([open]) { z-index: 20; }
  :host([icon-only][data-selected-icon]) { min-width: 0; }
  [hidden] { display: none !important; }

  [part="trigger"] {
    box-sizing: border-box;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    width: 100%;
    min-height: 2.75rem;
    padding: var(--biscuit-dropdownview-padding, calc(0.55rem * var(--biscuit-space-scale, 1)) calc(0.75rem * var(--biscuit-space-scale, 1)));
    border: var(--biscuit-dropdownview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-textview-border-color, light-dark(#a5a8b3, #656b7a))));
    border-radius: var(--biscuit-dropdownview-radius, 0.5rem);
    background: var(--biscuit-dropdownview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: inherit;
    font: inherit;
    text-align: left;
    cursor: pointer;
  }

  [part="trigger"]:focus-visible {
    outline: 2px solid var(--biscuit-dropdownview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: 2px;
  }

  [part="trigger"]:disabled { cursor: not-allowed; opacity: 0.55; }
  :host([icon-only][data-selected-icon]) [part="trigger"] {
    justify-content: center;
    width: 2.75rem;
    padding: 0.55rem;
  }
  :host([icon-only][data-selected-icon]) [part="value"] > span,
  :host([icon-only][data-selected-icon]) [part="chevron"] { display: none; }
  [part="value"], [part="option-content"] { display: inline-flex; align-items: center; gap: 0.5rem; min-width: 0; }
  [part="value"] { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  [part="icon"] { flex: none; width: 1.1em; height: 1.1em; }
  [part="chevron"] {
    flex: none;
    width: 0.5rem;
    height: 0.5rem;
    margin: -0.25rem 0.125rem 0 0;
    border-right: 2px solid currentColor;
    border-bottom: 2px solid currentColor;
    transform: rotate(45deg);
    transition: transform 120ms ease;
  }
  :host([open]) [part="chevron"] { transform: translateY(0.25rem) rotate(225deg); }

  [part="listbox"] {
    box-sizing: border-box;
    position: absolute;
    top: calc(100% + 0.375rem);
    left: 0;
    width: 100%;
    max-height: min(16rem, 70vh);
    overflow: auto;
    padding: 0.3rem;
    border: var(--biscuit-dropdownview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-textview-border-color, light-dark(#a5a8b3, #656b7a))));
    border-radius: var(--biscuit-dropdownview-radius, 0.5rem);
    background: var(--biscuit-dropdownview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    box-shadow: var(--biscuit-dropdownview-shadow, 0 12px 32px rgb(0 0 0 / 20%));
    color: inherit;
  }
  :host([icon-only]) [part="listbox"] { left: auto; right: 0; width: max-content; min-width: 12rem; }
  :host([icon-only][drop-left]) [part="listbox"] { left: 0; right: auto; }
  :host([drop-up]) [part="listbox"] { top: auto; bottom: calc(100% + 0.375rem); }

  [part="option"] {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.5rem;
    padding: 0.5rem 0.65rem;
    border-radius: 0.35rem;
    cursor: pointer;
  }
  [part="option"][data-active] {
    background: var(--biscuit-dropdownview-active-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f)));
    color: var(--biscuit-dropdownview-active-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff)));
  }
  [part="option"][aria-selected="true"]::after { content: "✓"; font-weight: 700; }
  [part="option"][aria-disabled="true"] { opacity: 0.45; cursor: not-allowed; }
`;
	var buttonViewStyles = `
  :host {
    box-sizing: border-box;
    display: inline-block;
    color-scheme: inherit;
    font: var(--biscuit-buttonview-font, 600 0.9375rem/1.4 system-ui, sans-serif);
    vertical-align: middle;
  }

  ${themeStyles}

  [hidden] { display: none !important; }
  [part="button"] {
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    gap: 0.55rem;
    width: 100%;
    min-height: var(--biscuit-buttonview-min-height, 2.75rem);
    padding: var(--biscuit-buttonview-padding, calc(0.625rem * var(--biscuit-space-scale, 1)) calc(1rem * var(--biscuit-space-scale, 1)));
    border: var(--biscuit-buttonview-border, 1px solid transparent);
    border-radius: var(--biscuit-buttonview-radius, 0.55rem);
    background: var(--biscuit-buttonview-background, var(--biscuit-theme-button-primary-background, light-dark(#6256ad, #c0bbff)));
    color: var(--biscuit-buttonview-color, var(--biscuit-theme-button-primary-color, light-dark(#fff, #211b3c)));
    font: inherit;
    white-space: nowrap;
    cursor: pointer;
    transition: background 120ms ease, filter 120ms ease, transform 120ms ease;
  }
  [part="button"]:hover:not(:disabled) { filter: brightness(1.08); }
  [part="button"]:active:not(:disabled) { transform: translateY(1px); }
  [part="button"]:focus-visible {
    outline: 2px solid var(--biscuit-buttonview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: 2px;
  }
  [part="button"]:disabled { cursor: not-allowed; opacity: 0.55; }
  :host([loading]) [part="button"]:disabled { opacity: 0.8; }
  [part="loading-indicator"] {
    --biscuit-loadingindicator-size: 0.9em;
    --biscuit-loadingindicator-color: inherit;
    color: inherit;
  }
  @media (prefers-reduced-motion: reduce) {
    [part="button"] { transition: none; }
  }
  :host([variant="secondary"]) [part="button"] {
    border: var(--biscuit-buttonview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    background: var(--biscuit-buttonview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-buttonview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
  }
  :host([variant="ghost"]) [part="button"] {
    background: var(--biscuit-buttonview-background, transparent);
    color: var(--biscuit-buttonview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
  }
  :host([variant="danger"]) [part="button"] {
    background: var(--biscuit-buttonview-background, var(--biscuit-theme-button-danger-background, light-dark(#be3e4a, #ff8d94)));
    color: var(--biscuit-buttonview-color, var(--biscuit-theme-button-danger-color, light-dark(#fff, #281215)));
  }
`;
	var loadingIndicatorStyles = `
  :host {
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    gap: var(--biscuit-loadingindicator-gap, 0.55rem);
    color-scheme: inherit;
    color: var(--biscuit-loadingindicator-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-loadingindicator-font, 500 0.9375rem/1.4 system-ui, sans-serif);
    vertical-align: middle;
  }

  ${themeStyles}

  [part="spinner"] {
    box-sizing: border-box;
    width: var(--biscuit-loadingindicator-size, 1.1em);
    height: var(--biscuit-loadingindicator-size, 1.1em);
    flex: none;
    border: var(--biscuit-loadingindicator-stroke, 2px) solid currentColor;
    border-right-color: transparent;
    border-radius: 50%;
    animation: biscuit-loading-spin 700ms linear infinite;
  }
  [part="label"]:empty { display: none; }
  :host([size="small"]) { --biscuit-loadingindicator-size: 0.85em; }
  :host([size="large"]) { --biscuit-loadingindicator-size: 1.5em; }
  @keyframes biscuit-loading-spin { to { transform: rotate(360deg); } }
  @media (prefers-reduced-motion: reduce) {
    [part="spinner"] { animation-duration: 2s; }
  }
`;
	var pageLoaderViewStyles = `
  :host {
    box-sizing: border-box;
    position: fixed;
    inset: 0;
    z-index: var(--biscuit-pageloaderview-z-index, 2000);
    display: grid;
    place-items: center;
    overflow: auto;
    overscroll-behavior: contain;
    color-scheme: inherit;
    background: var(--biscuit-pageloaderview-backdrop, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #171920))));
    color: var(--biscuit-pageloaderview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-pageloaderview-font, 400 1rem/1.5 system-ui, sans-serif);
  }
  ${themeStyles}
  :host(:not([open])) { display: none; }
  :host([mode="container"]) { position: absolute; z-index: var(--biscuit-pageloaderview-container-z-index, 1); }
  [part="panel"] {
    box-sizing: border-box;
    display: grid;
    justify-items: center;
    gap: 0.85rem;
    width: min(var(--biscuit-pageloaderview-panel-width, 24rem), calc(100% - 2rem));
    padding: var(--biscuit-pageloaderview-padding, 2rem 1.5rem);
    border: var(--biscuit-pageloaderview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-pageloaderview-radius, 0.875rem);
    background: var(--biscuit-pageloaderview-panel-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    box-shadow: var(--biscuit-pageloaderview-shadow, 0 12px 36px rgb(0 0 0 / 12%));
    text-align: center;
  }
  [part="panel"]:focus { outline: none; }
  [part="indicator"] {
    --biscuit-loadingindicator-size: 1.75rem;
    --biscuit-loadingindicator-gap: 0.85rem;
    --biscuit-loadingindicator-font: 650 1rem/1.4 system-ui, sans-serif;
  }
  [part="content"][hidden] { display: none; }
  ::slotted(*) { margin: 0; color: inherit; }
  :host([mode="container"]) [part="panel"] {
    width: min(var(--biscuit-pageloaderview-panel-width, 20rem), calc(100% - 1.5rem));
    padding: var(--biscuit-pageloaderview-padding, 1.5rem 1rem);
  }
  :host([indicator-only]) [part="panel"] {
    width: auto;
    padding: 0;
    border: 0;
    background: transparent;
    box-shadow: none;
  }
  :host([indicator-only]) [part="indicator"] {
    --biscuit-loadingindicator-size: var(--biscuit-pageloaderview-indicator-size, 4rem);
    --biscuit-loadingindicator-stroke: var(--biscuit-pageloaderview-indicator-stroke, 4px);
  }
  :host([indicator-only]) [part="indicator"]::part(label),
  :host([indicator-only]) [part="content"] { display: none; }
`;
	var badgeViewStyles = `
  :host {
    --_badge-tone: var(--biscuit-theme-badge-neutral, light-dark(#555b69, #d2d5df));
    box-sizing: border-box;
    display: inline-flex;
    align-items: center;
    max-width: 100%;
    vertical-align: middle;
    color-scheme: inherit;
    border: var(--biscuit-badgeview-border, var(--biscuit-theme-badge-border, 1px solid color-mix(in srgb, var(--_badge-tone) 30%, transparent)));
    border-radius: var(--biscuit-badgeview-radius, 999px);
    padding: var(--biscuit-badgeview-padding, calc(0.2rem * var(--biscuit-space-scale, 1)) calc(0.65rem * var(--biscuit-space-scale, 1)));
    background: var(--biscuit-badgeview-background, var(--biscuit-theme-badge-background, color-mix(in srgb, var(--_badge-tone) 12%, var(--biscuit-theme-surface, light-dark(#fff, #292c37)))));
    color: var(--biscuit-badgeview-color, var(--_badge-tone));
    font: var(--biscuit-badgeview-font, 650 0.75rem/1.35 system-ui, sans-serif);
    white-space: nowrap;
  }

  ${themeStyles}

  :host([variant="accent"]) { --_badge-tone: var(--biscuit-theme-badge-accent, light-dark(#5b4bb1, #c3baff)); }
  :host([variant="success"]) { --_badge-tone: var(--biscuit-theme-badge-success, light-dark(#176d4b, #8ce0ae)); }
  :host([variant="warning"]) { --_badge-tone: var(--biscuit-theme-badge-warning, light-dark(#8b5600, #ffd27a)); }
  :host([variant="danger"]) { --_badge-tone: var(--biscuit-theme-badge-danger, light-dark(#ad3442, #ff9ca4)); }
  :host([size="small"]) {
    padding: var(--biscuit-badgeview-padding, 0.12rem 0.45rem);
    font-size: 0.6875rem;
  }
  :host([size="large"]) {
    padding: var(--biscuit-badgeview-padding, 0.3rem 0.8rem);
    font-size: 0.875rem;
  }
  :host([dot]) {
    --_badge-dot-size: 0.7rem;
    width: var(--biscuit-badgeview-dot-size, var(--_badge-dot-size));
    height: var(--biscuit-badgeview-dot-size, var(--_badge-dot-size));
    min-width: var(--biscuit-badgeview-dot-size, var(--_badge-dot-size));
    padding: 0;
    border: 0;
    background: var(--biscuit-badgeview-dot-color, var(--_badge-tone));
  }
  :host([dot][size="small"]) { --_badge-dot-size: 0.55rem; }
  :host([dot][size="large"]) { --_badge-dot-size: 0.9rem; }
  [part="badge"] { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
  [hidden] { display: none !important; }
  :host([dot]) [part="badge"] {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
  [part="accessible-count"] {
    position: absolute;
    width: 1px;
    height: 1px;
    padding: 0;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
`;
	var BiscuitTextView = class extends HTMLElementBase {
		static formAssociated = true;
		static get observedAttributes() {
			return [
				"text",
				"editable",
				"resizable",
				"limit-resize-to-parent",
				"placeholder",
				"aria-label",
				"name",
				"required",
				"disabled"
			];
		}
		constructor() {
			super();
			this._internals = this.attachInternals?.() ?? null;
			if (!this._internals?.setFormValue) this._internals = null;
			this._text = "";
			this._resizeMaxWidth = null;
			this._resizeMaxHeight = null;
			this._onResizeStart = () => this._updateResizeLimit(false);
			this._onWindowResize = () => this._updateResizeLimit(true);
			this.addEventListener("pointerdown", this._onResizeStart);
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = textViewStyles;
			this._display = document.createElement("span");
			this._display.setAttribute("part", "text");
			this._editor = document.createElement("textarea");
			this._editor.setAttribute("part", "editor");
			this._editor.rows = 1;
			this._editor.addEventListener("input", () => {
				this.text = this._editor.value;
				this.dispatchEvent(new Event("input", {
					bubbles: true,
					composed: true
				}));
			});
			this._editor.addEventListener("change", () => this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			})));
			root.append(style, this._display, this._editor);
			this._renderMode();
		}
		connectedCallback() {
			this._defaultText ??= this.getAttribute("text") ?? "";
			this._syncForm();
			this._updateResizeLimit(true);
			if (globalThis.ResizeObserver && this.parentElement) {
				this._parentObserver = new ResizeObserver(() => this._updateResizeLimit(false));
				this._parentObserver.observe(this.parentElement);
			}
			globalThis.addEventListener?.("resize", this._onWindowResize);
		}
		disconnectedCallback() {
			this._parentObserver?.disconnect();
			this._parentObserver = null;
			globalThis.removeEventListener?.("resize", this._onWindowResize);
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue === newValue) return;
			if (name === "text") {
				this._text = newValue ?? "";
				this._display.textContent = this._text;
				if (this._editor.value !== this._text) this._editor.value = this._text;
				this._syncForm();
			} else if (name === "editable") {
				this._renderMode();
				this._syncForm();
			} else if (name === "resizable" || name === "limit-resize-to-parent") this._updateResizeLimit(true);
			else if (name === "placeholder") this._editor.placeholder = newValue ?? "";
			else if (name === "aria-label") this._editor.setAttribute("aria-label", newValue ?? "Text");
			else if (name === "name" || name === "required" || name === "disabled") this._syncForm();
		}
		get text() {
			return this._text;
		}
		set text(value) {
			this.setAttribute("text", String(value ?? ""));
		}
		get name() {
			return this.getAttribute("name") ?? "";
		}
		set name(value) {
			if (value == null) this.removeAttribute("name");
			else this.setAttribute("name", String(value));
		}
		get value() {
			return this.text;
		}
		set value(value) {
			this.text = value;
		}
		get required() {
			return this.hasAttribute("required");
		}
		set required(value) {
			this.toggleAttribute("required", Boolean(value));
		}
		get disabled() {
			return this.hasAttribute("disabled");
		}
		set disabled(value) {
			this.toggleAttribute("disabled", Boolean(value));
		}
		get validity() {
			return this._editor.validity;
		}
		get validationMessage() {
			return this._editor.validationMessage;
		}
		checkValidity() {
			this._syncForm();
			return this._editor.checkValidity();
		}
		reportValidity() {
			this._syncForm();
			return this._editor.reportValidity();
		}
		formResetCallback() {
			this.text = this._defaultText ?? "";
		}
		formDisabledCallback() {
			this._syncForm();
		}
		_syncForm() {
			if (!this._editor) return;
			this._editor.disabled = this.disabled || !this.editable;
			this._editor.required = this.required && this.editable;
			syncFormValue(this, this.name && this.editable && !this.disabled ? this.text : null);
			if (this._internals) this._internals.setValidity(this._editor.validity?.valid === false ? { valueMissing: true } : {}, this._editor.validationMessage || "", this._editor);
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		get editable() {
			return this.hasAttribute("editable");
		}
		set editable(value) {
			this.toggleAttribute("editable", Boolean(value));
		}
		get resizable() {
			return this.hasAttribute("resizable");
		}
		set resizable(value) {
			this.toggleAttribute("resizable", Boolean(value));
		}
		get limitResizeToParent() {
			return this.hasAttribute("limit-resize-to-parent");
		}
		set limitResizeToParent(value) {
			this.toggleAttribute("limit-resize-to-parent", Boolean(value));
		}
		get placeholder() {
			return this.getAttribute("placeholder") ?? "";
		}
		set placeholder(value) {
			if (value == null) this.removeAttribute("placeholder");
			else this.setAttribute("placeholder", String(value));
		}
		focus(options) {
			if (this.editable) this._editor.focus(options);
			else super.focus(options);
		}
		_renderMode() {
			const editable = this.editable;
			this._display.hidden = editable;
			this._editor.hidden = !editable;
			if (editable && !this._editor.hasAttribute("aria-label")) this._editor.setAttribute("aria-label", this.getAttribute("aria-label") ?? "Text");
		}
		_updateResizeLimit(allowGrowth) {
			if (!this.resizable || !this.limitResizeToParent) {
				this._resizeMaxWidth = null;
				this._resizeMaxHeight = null;
				this.style.removeProperty("--biscuit-textview-parent-max-width");
				this.style.removeProperty("--biscuit-textview-parent-max-height");
				return;
			}
			if (!this.isConnected || !this.parentElement) return;
			const parentRect = this.parentElement.getBoundingClientRect();
			const viewRect = this.getBoundingClientRect();
			const availableWidth = Math.max(0, parentRect.right - viewRect.left);
			const availableHeight = Math.max(0, parentRect.bottom - viewRect.top);
			if (!parentRect.width || !parentRect.height) return;
			this._resizeMaxWidth = allowGrowth || this._resizeMaxWidth == null ? availableWidth : Math.min(this._resizeMaxWidth, availableWidth);
			this._resizeMaxHeight = allowGrowth || this._resizeMaxHeight == null ? availableHeight : Math.min(this._resizeMaxHeight, availableHeight);
			this.style.setProperty("--biscuit-textview-parent-max-width", `${this._resizeMaxWidth}px`);
			this.style.setProperty("--biscuit-textview-parent-max-height", `${this._resizeMaxHeight}px`);
		}
	};
	var BiscuitCardView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["heading"];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = cardViewStyles;
			const card = document.createElement("article");
			card.setAttribute("part", "card");
			this._heading = document.createElement("h3");
			this._heading.setAttribute("part", "heading");
			this._heading.hidden = true;
			const body = document.createElement("div");
			body.setAttribute("part", "body");
			body.append(document.createElement("slot"));
			card.append(this._heading, body);
			root.append(style, card);
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (name !== "heading" || oldValue === newValue) return;
			this._heading.textContent = newValue ?? "";
			this._heading.hidden = !newValue;
		}
		get heading() {
			return this.getAttribute("heading") ?? "";
		}
		set heading(value) {
			if (value == null) this.removeAttribute("heading");
			else this.setAttribute("heading", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
	};
	var BiscuitDropdownView = class extends HTMLElementBase {
		static formAssociated = true;
		static get observedAttributes() {
			return [
				"value",
				"placeholder",
				"disabled",
				"aria-label",
				"name",
				"required",
				"icon-only"
			];
		}
		constructor() {
			super();
			this._internals = this.attachInternals?.() ?? null;
			if (!this._internals?.setFormValue) this._internals = null;
			this._options = [];
			this._optionNodes = [];
			this._value = "";
			this._activeIndex = -1;
			this._isOpen = false;
			this._onDocumentPointerDown = (event) => {
				if (!event.composedPath().includes(this)) this._setOpen(false);
			};
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = dropdownViewStyles;
			this._button = document.createElement("button");
			this._button.type = "button";
			this._button.setAttribute("part", "trigger");
			this._button.setAttribute("role", "combobox");
			this._button.setAttribute("aria-haspopup", "listbox");
			this._button.setAttribute("aria-expanded", "false");
			this._button.addEventListener("click", () => this._setOpen(!this._isOpen));
			this._button.addEventListener("keydown", (event) => this._onKeyDown(event));
			this._buttonLabel = document.createElement("span");
			this._buttonLabel.setAttribute("part", "value");
			const chevron = document.createElement("span");
			chevron.setAttribute("part", "chevron");
			chevron.setAttribute("aria-hidden", "true");
			this._button.append(this._buttonLabel, chevron);
			this._list = document.createElement("div");
			this._list.setAttribute("part", "listbox");
			this._list.setAttribute("role", "listbox");
			this._list.id = `biscuit-dropdown-list-${++dropdownListId}`;
			this._list.hidden = true;
			this._button.setAttribute("aria-controls", this._list.id);
			root.append(style, this._button, this._list);
		}
		connectedCallback() {
			this._syncOptions();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver(() => this._syncOptions());
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					characterData: true,
					attributes: true,
					attributeFilter: [
						"value",
						"label",
						"icon",
						"selected",
						"disabled"
					]
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._observer = null;
			this._setOpen(false);
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue !== newValue && this._button) this._syncOptions();
		}
		get value() {
			return this._value;
		}
		get iconOnly() {
			return this.hasAttribute("icon-only");
		}
		set iconOnly(value) {
			this.toggleAttribute("icon-only", Boolean(value));
		}
		get name() {
			return this.getAttribute("name") ?? "";
		}
		set name(value) {
			if (value == null) this.removeAttribute("name");
			else this.setAttribute("name", String(value));
		}
		get required() {
			return this.hasAttribute("required");
		}
		set required(value) {
			this.toggleAttribute("required", Boolean(value));
		}
		checkValidity() {
			this._syncOptions();
			return !this.required || Boolean(this._value);
		}
		reportValidity() {
			const valid = this.checkValidity();
			if (!valid) this._button.focus();
			return valid;
		}
		formResetCallback() {
			this.removeAttribute("value");
			this._syncOptions();
		}
		formDisabledCallback() {
			this._syncOptions();
		}
		set value(value) {
			this.setAttribute("value", String(value ?? ""));
		}
		get placeholder() {
			return this.getAttribute("placeholder") ?? "";
		}
		set placeholder(value) {
			if (value == null) this.removeAttribute("placeholder");
			else this.setAttribute("placeholder", String(value));
		}
		get disabled() {
			return this.hasAttribute("disabled");
		}
		set disabled(value) {
			this.toggleAttribute("disabled", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		focus(options) {
			this._button.focus(options);
		}
		_syncOptions() {
			this._options = Array.from(this.children).filter((child) => child.tagName === "OPTION").map((child) => ({
				value: child.getAttribute("value") ?? child.textContent.trim(),
				label: child.getAttribute("label") ?? child.textContent.trim(),
				icon: child.getAttribute("icon"),
				disabled: child.hasAttribute("disabled"),
				selected: child.hasAttribute("selected")
			}));
			const requested = this.getAttribute("value");
			let selectedIndex = requested === null ? this._options.findIndex((option) => option.selected && !option.disabled) : this._options.findIndex((option) => option.value === requested && !option.disabled);
			if (requested === null && selectedIndex < 0 && !this.hasAttribute("placeholder")) selectedIndex = this._options.findIndex((option) => !option.disabled);
			this._value = selectedIndex < 0 ? "" : this._options[selectedIndex].value;
			syncFormValue(this, this.name && !this.disabled && this._value ? this._value : null);
			this._internals?.setValidity(this.required && !this._value ? { valueMissing: true } : {}, this.required && !this._value ? "Choose an option." : "", this._button);
			const label = selectedIndex < 0 ? this.placeholder || "Select…" : this._options[selectedIndex].label;
			const selectedIcon = selectedIndex < 0 ? null : this._options[selectedIndex].icon;
			this.toggleAttribute("data-selected-icon", Boolean(selectedIcon && iconSvgs[selectedIcon]));
			this._buttonLabel.replaceChildren();
			if (selectedIcon && iconSvgs[selectedIcon]) {
				const icon = createIcon(selectedIcon);
				icon.setAttribute("part", "icon");
				const text = document.createElement("span");
				text.textContent = label;
				this._buttonLabel.append(icon, text);
			} else this._buttonLabel.textContent = label;
			this._button.disabled = this.disabled || !this._options.some((option) => !option.disabled);
			const accessibleLabel = this.getAttribute("aria-label");
			if (accessibleLabel || this.iconOnly) this._button.setAttribute("aria-label", accessibleLabel ? `${accessibleLabel}: ${label}` : label);
			else this._button.removeAttribute("aria-label");
			if (accessibleLabel) this._list.setAttribute("aria-label", accessibleLabel);
			else this._list.removeAttribute("aria-label");
			this._optionNodes = this._options.map((option, index) => {
				const node = document.createElement("div");
				node.id = `${this._list.id}-option-${index}`;
				node.setAttribute("part", "option");
				node.setAttribute("role", "option");
				node.setAttribute("aria-selected", String(index === selectedIndex));
				if (option.disabled) node.setAttribute("aria-disabled", "true");
				if (option.icon && iconSvgs[option.icon]) {
					const content = document.createElement("span");
					content.setAttribute("part", "option-content");
					const icon = createIcon(option.icon);
					icon.setAttribute("part", "icon");
					const label = document.createElement("span");
					label.textContent = option.label;
					content.append(icon, label);
					node.append(content);
				} else node.textContent = option.label;
				node.addEventListener("pointerenter", () => {
					if (!option.disabled) this._setActive(index);
				});
				node.addEventListener("click", () => this._select(index));
				return node;
			});
			this._list.replaceChildren(...this._optionNodes);
			if (this._isOpen && this._button.disabled) this._setOpen(false);
			else if (this._isOpen) this._setActive(selectedIndex < 0 ? this._firstEnabled() : selectedIndex);
		}
		_firstEnabled() {
			return this._options.findIndex((option) => !option.disabled);
		}
		_setActive(index) {
			this._activeIndex = index;
			this._optionNodes.forEach((node, nodeIndex) => {
				if (nodeIndex === index) node.setAttribute("data-active", "");
				else node.removeAttribute("data-active");
			});
			if (index < 0) this._button.removeAttribute("aria-activedescendant");
			else {
				this._button.setAttribute("aria-activedescendant", this._optionNodes[index].id);
				this._optionNodes[index].scrollIntoView?.({ block: "nearest" });
			}
		}
		_setOpen(open) {
			if (open && (this.disabled || this._firstEnabled() < 0)) return;
			if (open === this._isOpen) return;
			this._isOpen = open;
			this.toggleAttribute("open", open);
			this._list.hidden = !open;
			this._button.setAttribute("aria-expanded", String(open));
			if (open) {
				const selectedIndex = this._options.findIndex((option) => option.value === this._value && !option.disabled);
				this._setActive(selectedIndex < 0 ? this._firstEnabled() : selectedIndex);
				const bounds = this.getBoundingClientRect?.();
				if (bounds && globalThis.innerHeight) this.toggleAttribute("drop-up", globalThis.innerHeight - bounds.bottom < this._list.offsetHeight + 8 && bounds.top > globalThis.innerHeight - bounds.bottom);
				if (bounds && this.iconOnly && globalThis.innerWidth) this.toggleAttribute("drop-left", bounds.right - this._list.offsetWidth < 8 && globalThis.innerWidth - bounds.left >= this._list.offsetWidth + 8);
				document.addEventListener("pointerdown", this._onDocumentPointerDown);
			} else {
				this._button.removeAttribute("aria-activedescendant");
				this.removeAttribute("drop-up");
				this.removeAttribute("drop-left");
				document.removeEventListener("pointerdown", this._onDocumentPointerDown);
			}
		}
		_moveActive(direction) {
			const enabled = this._options.flatMap((option, index) => option.disabled ? [] : [index]);
			if (!enabled.length) return;
			const next = (enabled.indexOf(this._activeIndex) + direction + enabled.length) % enabled.length;
			this._setActive(enabled[next]);
		}
		_onKeyDown(event) {
			if (this.disabled) return;
			if (event.key === "ArrowDown" || event.key === "ArrowUp") {
				event.preventDefault();
				if (!this._isOpen) this._setOpen(true);
				else this._moveActive(event.key === "ArrowDown" ? 1 : -1);
			} else if (event.key === "Home" || event.key === "End") {
				event.preventDefault();
				this._setOpen(true);
				const index = event.key === "Home" ? this._firstEnabled() : this._options.findLastIndex((option) => !option.disabled);
				if (index >= 0) this._setActive(index);
			} else if (event.key === "Enter" || event.key === " ") {
				event.preventDefault();
				if (this._isOpen) this._select(this._activeIndex);
				else this._setOpen(true);
			} else if (event.key === "Escape" && this._isOpen) {
				event.preventDefault();
				this._setOpen(false);
			} else if (event.key === "Tab") this._setOpen(false);
		}
		_select(index) {
			const option = this._options[index];
			if (!option || option.disabled) return;
			const previous = this._value;
			this.value = option.value;
			this._setOpen(false);
			this._button.focus();
			if (this._value !== previous) {
				this.dispatchEvent(new Event("input", {
					bubbles: true,
					composed: true
				}));
				this.dispatchEvent(new Event("change", {
					bubbles: true,
					composed: true
				}));
			}
		}
	};
	var BiscuitLoadingIndicator = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["label"];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = loadingIndicatorStyles;
			const spinner = document.createElement("span");
			spinner.setAttribute("part", "spinner");
			spinner.setAttribute("aria-hidden", "true");
			this._label = document.createElement("span");
			this._label.setAttribute("part", "label");
			root.append(style, spinner, this._label);
			this._renderLabel();
		}
		connectedCallback() {
			if (!this.hasAttribute("role")) this.setAttribute("role", "status");
			if (!this.hasAttribute("aria-live")) this.setAttribute("aria-live", "polite");
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (name === "label" && oldValue !== newValue) this._renderLabel();
		}
		get label() {
			return this.getAttribute("label") ?? "Loading…";
		}
		set label(value) {
			if (value == null) this.removeAttribute("label");
			else this.setAttribute("label", String(value));
		}
		get size() {
			return this.getAttribute("size") ?? "medium";
		}
		set size(value) {
			if (value == null || value === "medium") this.removeAttribute("size");
			else this.setAttribute("size", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		_renderLabel() {
			this._label.textContent = this.label;
		}
	};
	var BiscuitPageLoaderView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"open",
				"mode",
				"message"
			];
		}
		constructor() {
			super();
			this._inertSiblings = null;
			this._containerInertSiblings = null;
			this._containerParent = null;
			this._positionedParent = null;
			this._restoreFocus = null;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = pageLoaderViewStyles;
			this._panel = document.createElement("div");
			this._panel.setAttribute("part", "panel");
			this._panel.tabIndex = -1;
			this._indicator = document.createElement(LOADINGINDICATOR_TAG_NAME);
			this._indicator.setAttribute("part", "indicator");
			this._indicator.setAttribute("size", "large");
			this._indicator.setAttribute("aria-hidden", "true");
			this._contentSlot = document.createElement("slot");
			this._contentSlot.setAttribute("part", "content");
			this._contentSlot.hidden = true;
			this._contentSlot.addEventListener("slotchange", () => this._syncSlot());
			this._panel.append(this._indicator, this._contentSlot);
			root.append(style, this._panel);
			this._renderMessage();
		}
		connectedCallback() {
			this._syncSlot();
			this._syncState();
		}
		disconnectedCallback() {
			this._releasePage();
			this._releaseContainer();
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue === newValue) return;
			if (name === "message") this._renderMessage();
			else this._syncState();
		}
		get open() {
			return this.hasAttribute("open");
		}
		set open(value) {
			this.toggleAttribute("open", Boolean(value));
		}
		get mode() {
			return this.getAttribute("mode") === "container" ? "container" : "page";
		}
		set mode(value) {
			if (value === "container") this.setAttribute("mode", "container");
			else this.removeAttribute("mode");
		}
		get message() {
			return this.getAttribute("message") ?? "Loading…";
		}
		set message(value) {
			if (value == null) this.removeAttribute("message");
			else this.setAttribute("message", String(value));
		}
		get indicatorOnly() {
			return this.hasAttribute("indicator-only");
		}
		set indicatorOnly(value) {
			this.toggleAttribute("indicator-only", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		show() {
			this.open = true;
		}
		hide() {
			this.open = false;
		}
		_renderMessage() {
			this._indicator.label = this.message;
			this._panel.setAttribute("aria-label", this.message);
		}
		_syncSlot() {
			const nodes = this._contentSlot.assignedNodes?.({ flatten: true }) ?? this.children;
			this._contentSlot.hidden = !Array.from(nodes).some((node) => node.nodeType === 1 || node.textContent?.trim());
		}
		_syncState() {
			const active = this.open && this.isConnected;
			const page = this.mode === "page";
			this._panel.setAttribute("role", page ? "dialog" : "status");
			if (page) this._panel.setAttribute("aria-modal", "true");
			else this._panel.removeAttribute("aria-modal");
			this._panel.setAttribute("aria-live", "polite");
			this.setAttribute("aria-busy", String(Boolean(active)));
			if (active && page) {
				this._releaseContainer();
				this._lockPage();
			} else if (active) {
				this._releasePage();
				this._lockContainer();
			} else {
				this._releasePage();
				this._releaseContainer();
			}
		}
		_lockPage() {
			if (this._inertSiblings) return;
			this._restoreFocus = document.activeElement;
			this._inertSiblings = [];
			let node = this;
			while (node.parentElement) {
				const parent = node.parentElement;
				for (const sibling of parent.children) {
					if (sibling === node) continue;
					this._makeInert(sibling);
					this._inertSiblings.push(sibling);
				}
				node = parent;
				if (parent === document.body) break;
			}
			this._panel.focus();
		}
		_releasePage() {
			if (!this._inertSiblings) return;
			for (const sibling of this._inertSiblings) this._restoreInert(sibling);
			this._inertSiblings = null;
			this._restoreFocus?.focus?.();
			this._restoreFocus = null;
		}
		_lockContainer() {
			const parent = this.parentElement;
			if (!parent || this._containerParent === parent) return;
			this._releaseContainer();
			this._containerParent = parent;
			this._containerInertSiblings = [];
			for (const sibling of parent.children) {
				if (sibling === this) continue;
				this._makeInert(sibling);
				this._containerInertSiblings.push(sibling);
			}
			const lock = pageLoaderPositionLocks.get(parent);
			if (lock) lock.count += 1;
			else {
				const position = globalThis.getComputedStyle?.(parent).position ?? parent.style.getPropertyValue("position") ?? "static";
				if (position !== "static" && position !== "") return;
				pageLoaderPositionLocks.set(parent, {
					count: 1,
					original: parent.style.getPropertyValue("position"),
					priority: parent.style.getPropertyPriority?.("position") ?? ""
				});
				parent.style.setProperty("position", "relative");
			}
			this._positionedParent = parent;
		}
		_releaseContainer() {
			if (this._containerInertSiblings) {
				for (const sibling of this._containerInertSiblings) this._restoreInert(sibling);
				this._containerInertSiblings = null;
			}
			this._containerParent = null;
			if (!this._positionedParent) return;
			const parent = this._positionedParent;
			const lock = pageLoaderPositionLocks.get(parent);
			if (lock) {
				lock.count -= 1;
				if (lock.count === 0) {
					if (parent.style.getPropertyValue("position") === "relative") {
						if (lock.original) parent.style.setProperty("position", lock.original, lock.priority);
						else parent.style.removeProperty("position");
					}
					pageLoaderPositionLocks.delete(parent);
				}
			}
			this._positionedParent = null;
		}
		_makeInert(element) {
			const lock = pageLoaderInertLocks.get(element);
			if (lock) lock.count += 1;
			else pageLoaderInertLocks.set(element, {
				count: 1,
				original: element.inert
			});
			element.inert = true;
		}
		_restoreInert(element) {
			const lock = pageLoaderInertLocks.get(element);
			if (!lock) return;
			lock.count -= 1;
			if (lock.count === 0) {
				element.inert = lock.original;
				pageLoaderInertLocks.delete(element);
			}
		}
	};
	var BiscuitBadgeView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"label",
				"count",
				"max",
				"dot"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = badgeViewStyles;
			this._badge = document.createElement("span");
			this._badge.setAttribute("part", "badge");
			this._text = document.createElement("span");
			this._text.setAttribute("part", "text");
			this._slot = document.createElement("slot");
			this._slot.setAttribute("part", "content");
			this._slot.addEventListener("slotchange", () => this._render());
			this._accessibleCount = document.createElement("span");
			this._accessibleCount.setAttribute("part", "accessible-count");
			this._badge.append(this._text, this._slot, this._accessibleCount);
			root.append(style, this._badge);
			this._render();
		}
		connectedCallback() {
			this._render();
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue !== newValue) this._render();
		}
		get label() {
			return this.getAttribute("label") ?? "";
		}
		set label(value) {
			if (value == null) this.removeAttribute("label");
			else this.setAttribute("label", String(value));
		}
		get count() {
			if (!this.hasAttribute("count")) return null;
			const count = Number(this.getAttribute("count"));
			return Number.isInteger(count) && count >= 0 ? count : null;
		}
		set count(value) {
			if (value == null) this.removeAttribute("count");
			else {
				const count = Number(value);
				if (!Number.isInteger(count) || count < 0) throw new RangeError("BadgeView count must be a non-negative integer");
				this.setAttribute("count", String(count));
			}
		}
		get max() {
			const max = Number(this.getAttribute("max"));
			return this.hasAttribute("max") && Number.isInteger(max) && max >= 0 ? max : 99;
		}
		set max(value) {
			if (value == null) this.removeAttribute("max");
			else {
				const max = Number(value);
				if (!Number.isInteger(max) || max < 0) throw new RangeError("BadgeView max must be a non-negative integer");
				this.setAttribute("max", String(max));
			}
		}
		get dot() {
			return this.hasAttribute("dot");
		}
		set dot(value) {
			this.toggleAttribute("dot", Boolean(value));
		}
		get variant() {
			return this.getAttribute("variant") ?? "neutral";
		}
		set variant(value) {
			if (value == null || value === "neutral") this.removeAttribute("variant");
			else this.setAttribute("variant", String(value));
		}
		get size() {
			return this.getAttribute("size") ?? "medium";
		}
		set size(value) {
			if (value == null || value === "medium") this.removeAttribute("size");
			else this.setAttribute("size", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		_render() {
			const count = this.count;
			const label = this.label;
			if (this.dot) {
				this._text.textContent = label || this.textContent?.trim() || "";
				this._text.hidden = false;
				this._text.removeAttribute("aria-hidden");
				this._slot.hidden = true;
				this._accessibleCount.hidden = true;
			} else if (count !== null) {
				this._text.textContent = count > this.max ? `${this.max}+` : String(count);
				this._text.hidden = false;
				if (label) this._text.setAttribute("aria-hidden", "true");
				else this._text.removeAttribute("aria-hidden");
				this._slot.hidden = true;
				this._accessibleCount.textContent = label ? `${count} ${label}` : "";
				this._accessibleCount.hidden = !label;
			} else {
				this._text.textContent = label;
				this._text.hidden = !label;
				this._text.removeAttribute("aria-hidden");
				this._slot.hidden = Boolean(label);
				this._accessibleCount.hidden = true;
			}
		}
	};
	var BiscuitButtonView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"disabled",
				"loading",
				"loading-label",
				"aria-label"
			];
		}
		constructor() {
			super();
			this.action = null;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = buttonViewStyles;
			this._button = document.createElement("button");
			this._button.type = "button";
			this._button.setAttribute("part", "button");
			this._button.addEventListener("click", () => {
				if (typeof this.action === "function") this.run().catch(() => {});
			});
			this._content = document.createElement("span");
			this._content.setAttribute("part", "content");
			this._content.append(document.createElement("slot"));
			this._indicator = document.createElement(LOADINGINDICATOR_TAG_NAME);
			this._indicator.setAttribute("part", "loading-indicator");
			this._indicator.setAttribute("aria-hidden", "true");
			this._indicator.setAttribute("label", "");
			this._loadingText = document.createElement("span");
			this._loadingText.setAttribute("part", "loading-label");
			this._loadingText.setAttribute("role", "status");
			this._button.append(this._content, this._indicator, this._loadingText);
			root.append(style, this._button);
			this._renderState();
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue !== newValue && this._button) this._renderState();
		}
		get disabled() {
			return this.hasAttribute("disabled");
		}
		set disabled(value) {
			this.toggleAttribute("disabled", Boolean(value));
		}
		get loading() {
			return this.hasAttribute("loading");
		}
		set loading(value) {
			this.toggleAttribute("loading", Boolean(value));
		}
		get loadingLabel() {
			return this.getAttribute("loading-label") ?? "Working…";
		}
		set loadingLabel(value) {
			if (value == null) this.removeAttribute("loading-label");
			else this.setAttribute("loading-label", String(value));
		}
		get variant() {
			return this.getAttribute("variant") ?? "primary";
		}
		set variant(value) {
			if (value == null || value === "primary") this.removeAttribute("variant");
			else this.setAttribute("variant", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		focus(options) {
			this._button.focus(options);
		}
		click() {
			this._button.click();
		}
		async run(action = this.action) {
			if (this.disabled || this.loading || typeof action !== "function") return;
			let result;
			try {
				result = action(this);
			} catch (error) {
				this._reportActionError(error);
				throw error;
			}
			if (!result || typeof result.then !== "function") return result;
			this.loading = true;
			try {
				return await result;
			} catch (error) {
				this._reportActionError(error);
				throw error;
			} finally {
				this.loading = false;
			}
		}
		_reportActionError(error) {
			const event = new Event("actionerror", {
				bubbles: true,
				composed: true
			});
			Object.defineProperty(event, "detail", { value: error });
			this.dispatchEvent(event);
		}
		_renderState() {
			const loading = this.loading;
			this._button.disabled = this.disabled || loading;
			this._button.setAttribute("aria-busy", String(loading));
			this._content.hidden = loading;
			this._indicator.hidden = !loading;
			this._loadingText.hidden = !loading;
			this._loadingText.textContent = this.loadingLabel;
			const label = this.getAttribute("aria-label");
			if (loading) this._button.setAttribute("aria-label", this.loadingLabel || "Working…");
			else if (label) this._button.setAttribute("aria-label", label);
			else this._button.removeAttribute("aria-label");
		}
	};
	function defineTextView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit TextView requires a browser with Custom Elements");
		const existing = registry.get(TEXTVIEW_TAG_NAME);
		if (existing && existing !== BiscuitTextView) throw new Error(`<${TEXTVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(TEXTVIEW_TAG_NAME, BiscuitTextView);
		return BiscuitTextView;
	}
	function defineCardView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit CardView requires a browser with Custom Elements");
		const existing = registry.get(CARDVIEW_TAG_NAME);
		if (existing && existing !== BiscuitCardView) throw new Error(`<${CARDVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(CARDVIEW_TAG_NAME, BiscuitCardView);
		return BiscuitCardView;
	}
	function defineDropdownView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit DropdownView requires a browser with Custom Elements");
		const existing = registry.get(DROPDOWNVIEW_TAG_NAME);
		if (existing && existing !== BiscuitDropdownView) throw new Error(`<${DROPDOWNVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(DROPDOWNVIEW_TAG_NAME, BiscuitDropdownView);
		return BiscuitDropdownView;
	}
	function defineLoadingIndicator() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit LoadingIndicator requires a browser with Custom Elements");
		const existing = registry.get(LOADINGINDICATOR_TAG_NAME);
		if (existing && existing !== BiscuitLoadingIndicator) throw new Error(`<${LOADINGINDICATOR_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(LOADINGINDICATOR_TAG_NAME, BiscuitLoadingIndicator);
		return BiscuitLoadingIndicator;
	}
	function definePageLoaderView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit PageLoaderView requires a browser with Custom Elements");
		defineLoadingIndicator();
		const existing = registry.get(PAGELOADERVIEW_TAG_NAME);
		if (existing && existing !== BiscuitPageLoaderView) throw new Error(`<${PAGELOADERVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(PAGELOADERVIEW_TAG_NAME, BiscuitPageLoaderView);
		return BiscuitPageLoaderView;
	}
	function defineBadgeView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit BadgeView requires a browser with Custom Elements");
		const existing = registry.get(BADGEVIEW_TAG_NAME);
		if (existing && existing !== BiscuitBadgeView) throw new Error(`<${BADGEVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(BADGEVIEW_TAG_NAME, BiscuitBadgeView);
		return BiscuitBadgeView;
	}
	function defineButtonView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit ButtonView requires a browser with Custom Elements");
		defineLoadingIndicator();
		const existing = registry.get(BUTTONVIEW_TAG_NAME);
		if (existing && existing !== BiscuitButtonView) throw new Error(`<${BUTTONVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(BUTTONVIEW_TAG_NAME, BiscuitButtonView);
		return BiscuitButtonView;
	}
	function createTextView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit TextView requires a browser document");
		defineTextView();
		const element = createConfiguredElement(TEXTVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "text")) element.text = options.text;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "editable")) element.editable = options.editable;
		if (Object.hasOwn(options, "resizable")) element.resizable = options.resizable;
		if (Object.hasOwn(options, "limitResizeToParent")) element.limitResizeToParent = options.limitResizeToParent;
		if (Object.hasOwn(options, "placeholder")) element.placeholder = options.placeholder;
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		return element;
	}
	function createCardView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit CardView requires a browser document");
		defineCardView();
		const element = createConfiguredElement(CARDVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "heading")) element.heading = options.heading;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (options.content != null) element.append(options.content);
		return element;
	}
	function createDropdownView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit DropdownView requires a browser document");
		defineDropdownView();
		const element = createConfiguredElement(DROPDOWNVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "placeholder")) element.placeholder = options.placeholder;
		if (Object.hasOwn(options, "disabled")) element.disabled = options.disabled;
		if (Object.hasOwn(options, "iconOnly")) element.iconOnly = options.iconOnly;
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		for (const item of options.options ?? []) {
			const option = document.createElement("option");
			if (typeof item === "string") {
				option.setAttribute("value", item);
				option.textContent = item;
			} else {
				option.setAttribute("value", String(item.value));
				option.textContent = String(item.label ?? item.value);
				if (item.icon) option.setAttribute("icon", String(item.icon));
				if (item.disabled) option.setAttribute("disabled", "");
			}
			element.append(option);
		}
		if (Object.hasOwn(options, "value")) element.value = options.value;
		else element._syncOptions();
		return element;
	}
	function createLoadingIndicator(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit LoadingIndicator requires a browser document");
		defineLoadingIndicator();
		const element = createConfiguredElement(LOADINGINDICATOR_TAG_NAME, options);
		if (Object.hasOwn(options, "label")) element.label = options.label;
		if (Object.hasOwn(options, "size")) element.size = options.size;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		return element;
	}
	function createPageLoaderView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit PageLoaderView requires a browser document");
		definePageLoaderView();
		const element = createConfiguredElement(PAGELOADERVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "message")) element.message = options.message;
		if (Object.hasOwn(options, "mode")) element.mode = options.mode;
		if (Object.hasOwn(options, "indicatorOnly")) element.indicatorOnly = options.indicatorOnly;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (options.content != null) element.append(options.content);
		if (Object.hasOwn(options, "open")) element.open = options.open;
		return element;
	}
	function createBadgeView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit BadgeView requires a browser document");
		defineBadgeView();
		const element = createConfiguredElement(BADGEVIEW_TAG_NAME, options);
		for (const name of [
			"label",
			"count",
			"max",
			"dot",
			"variant",
			"size",
			"theme"
		]) if (Object.hasOwn(options, name)) element[name] = options[name];
		if (options.content != null) element.append(options.content);
		return element;
	}
	function createButtonView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit ButtonView requires a browser document");
		defineButtonView();
		const element = createConfiguredElement(BUTTONVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "label")) element.textContent = String(options.label ?? "");
		if (Object.hasOwn(options, "variant")) element.variant = options.variant;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "disabled")) element.disabled = options.disabled;
		if (Object.hasOwn(options, "loading")) element.loading = options.loading;
		if (Object.hasOwn(options, "loadingLabel")) element.loadingLabel = options.loadingLabel;
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		if (Object.hasOwn(options, "action")) element.action = options.action;
		return element;
	}
	function defineCore() {
		defineTextView();
		defineCardView();
		defineDropdownView();
		defineLoadingIndicator();
		definePageLoaderView();
		defineBadgeView();
		defineButtonView();
		defineCalendarView();
		defineDatePickerView();
	}
	// src/groups/layout.js
	var layout_exports = /* @__PURE__ */ __exportAll({
		BiscuitFooterView: () => BiscuitFooterView,
		BiscuitNavBarView: () => BiscuitNavBarView,
		BiscuitSidebarView: () => BiscuitSidebarView,
		BiscuitSplitView: () => BiscuitSplitView,
		BiscuitTabLayout: () => BiscuitTabLayout,
		createFooterView: () => createFooterView,
		createNavBarView: () => createNavBarView,
		createSidebarView: () => createSidebarView,
		createSplitView: () => createSplitView,
		createTabLayout: () => createTabLayout,
		defineFooterView: () => defineFooterView,
		defineLayout: () => defineLayout,
		defineNavBarView: () => defineNavBarView,
		defineSidebarView: () => defineSidebarView,
		defineSplitView: () => defineSplitView,
		defineTabLayout: () => defineTabLayout
	});
	var TABLAYOUT_TAG_NAME = "biscuit-tablayout";
	var SPLITVIEW_TAG_NAME = "biscuit-splitview";
	var NAVBARVIEW_TAG_NAME = "biscuit-navbarview";
	var FOOTERVIEW_TAG_NAME = "biscuit-footerview";
	var SIDEBARVIEW_TAG_NAME = "biscuit-sidebarview";
	var tabLayoutId = 0;
	var sidebarViewId = 0;
	var splitViewId = 0;
	var navBarViewId = 0;
	var footerViewId = 0;
	var tabLayoutStyles = `
  :host {
    box-sizing: border-box;
    display: block;
    min-width: 0;
    color-scheme: inherit;
    border: var(--biscuit-tablayout-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-tablayout-radius, 0.75rem);
    background: var(--biscuit-tablayout-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-tablayout-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-tablayout-font, inherit);
  }

  ${themeStyles}

  [hidden] { display: none !important; }
  [part="layout"] { min-width: 0; }
  [part="tablist"] {
    display: flex;
    gap: 0.25rem;
    min-width: 0;
    overflow-x: auto;
    padding: 0.5rem 0.625rem 0;
    border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
  }
  [part="tab"] {
    flex: none;
    position: relative;
    min-height: 2.5rem;
    padding: var(--biscuit-tablayout-tab-padding, calc(0.5rem * var(--biscuit-space-scale, 1)) calc(0.875rem * var(--biscuit-space-scale, 1)));
    border: 0;
    border-radius: var(--biscuit-tablayout-tab-radius, 0.4rem 0.4rem 0 0);
    background: transparent;
    color: inherit;
    font: inherit;
    font-weight: 550;
    white-space: nowrap;
    cursor: pointer;
  }
  [part="tab"]:hover:not(:disabled) {
    background: var(--biscuit-tablayout-hover-background, light-dark(#f2f0ff, #373947));
  }
  [part="tab"]:focus-visible {
    outline: 2px solid var(--biscuit-tablayout-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: -2px;
  }
  [part="tab"][aria-selected="true"] {
    color: var(--biscuit-tablayout-active-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#5e55bc, #c5bfff))));
  }
  [part="tab"][aria-selected="true"]::after {
    content: "";
    position: absolute;
    right: 0.5rem;
    bottom: 0;
    left: 0.5rem;
    height: 2px;
    border-radius: 2px;
    background: currentColor;
  }
  [part="tab"]:disabled { opacity: 0.45; cursor: not-allowed; }
  [part="tabpanel"] {
    min-width: 0;
    min-height: var(--biscuit-tablayout-panel-min-height, 6rem);
    padding: var(--biscuit-tablayout-panel-padding, calc(1.25rem * var(--biscuit-space-scale, 1)));
  }
  ::slotted(biscuit-tab) { display: block; }

  :host([orientation="vertical"]) [part="layout"] { display: grid; grid-template-columns: max-content minmax(0, 1fr); }
  :host([orientation="vertical"]) [part="tablist"] {
    flex-direction: column;
    align-items: stretch;
    overflow-x: visible;
    padding: 0.625rem 0 0.625rem 0.625rem;
    border-right: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    border-bottom: 0;
  }
  :host([orientation="vertical"]) [part="tab"] {
    width: 100%;
    border-radius: var(--biscuit-tablayout-tab-radius, 0.4rem 0 0 0.4rem);
    text-align: left;
  }
  :host([orientation="vertical"]) [part="tab"][aria-selected="true"]::after {
    top: 0.5rem;
    right: 0;
    bottom: 0.5rem;
    left: auto;
    width: 2px;
    height: auto;
  }
  :host([variant="pills"]) [part="tablist"] { gap: 0.375rem; padding-bottom: 0.5rem; border: 0; }
  :host([variant="pills"]) [part="tab"] { border-radius: var(--biscuit-tablayout-tab-radius, 0.5rem); }
  :host([variant="pills"]) [part="tab"][aria-selected="true"] {
    background: var(--biscuit-tablayout-active-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f)));
    color: var(--biscuit-tablayout-active-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff)));
  }
  :host([variant="pills"]) [part="tab"][aria-selected="true"]::after { content: none; }
  :host([orientation="vertical"][variant="pills"]) [part="tablist"] { padding-right: 0.625rem; }
  @media (max-width: 420px) {
    :host([orientation="vertical"]) [part="layout"] { grid-template-columns: minmax(0, 1fr); }
    :host([orientation="vertical"]) [part="tablist"] {
      flex-direction: row;
      overflow-x: auto;
      padding: 0.5rem 0.625rem;
      border-right: 0;
      border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    }
    :host([orientation="vertical"]) [part="tab"] { width: auto; text-align: center; }
  }
`;
	var splitViewStyles = `
  :host {
    box-sizing: border-box; display: block; width: 100%; height: var(--biscuit-splitview-height, 14rem);
    min-width: 0; min-height: 0; overflow: hidden; color-scheme: inherit;
    border: var(--biscuit-splitview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-splitview-radius, 0.75rem);
    background: var(--biscuit-splitview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-splitview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-splitview-font, 400 0.875rem/1.45 system-ui, sans-serif);
  }
  ${themeStyles}
  [part="layout"] { display: grid; width: 100%; height: 100%; min-width: 0; min-height: 0; grid-template-columns: minmax(0, var(--_split-first, 50fr)) minmax(0, var(--_split-second, 50fr)); }
  :host([resizable]) [part="layout"] { grid-template-columns: minmax(0, var(--_split-first, 50fr)) var(--biscuit-splitview-handle-size, 0.75rem) minmax(0, var(--_split-second, 50fr)); }
  :host([orientation="vertical"]) [part="layout"] { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, var(--_split-first, 50fr)) minmax(0, var(--_split-second, 50fr)); }
  :host([orientation="vertical"][resizable]) [part="layout"] { grid-template-rows: minmax(0, var(--_split-first, 50fr)) var(--biscuit-splitview-handle-size, 0.75rem) minmax(0, var(--_split-second, 50fr)); }
  [part~="pane"] { min-width: 0; min-height: 0; overflow: auto; padding: var(--biscuit-splitview-pane-padding, calc(1rem * var(--biscuit-space-scale, 1))); }
  [part~="first"] { border-right: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))); }
  :host([orientation="vertical"]) [part~="first"] { border-right: 0; border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))); }
  :host([resizable]) [part~="first"] { border: 0; }
  [part="handle"] { display: none; align-items: center; justify-content: center; min-width: 0; min-height: 0; padding: 0; border: 0; background: var(--biscuit-splitview-handle-background, color-mix(in srgb, currentColor 8%, transparent)); color: inherit; touch-action: none; cursor: col-resize; }
  :host([resizable]) [part="handle"] { display: flex; }
  :host([orientation="vertical"]) [part="handle"] { cursor: row-resize; }
  [part="handle"]::before { content: ""; display: block; width: 2px; height: 2rem; border-radius: 2px; background: var(--biscuit-splitview-grip-color, color-mix(in srgb, currentColor 55%, transparent)); }
  :host([orientation="vertical"]) [part="handle"]::before { width: 2rem; height: 2px; }
  [part="handle"]:hover { background: var(--biscuit-splitview-handle-hover-background, color-mix(in srgb, currentColor 15%, transparent)); }
  [part="handle"]:focus-visible { outline: 2px solid var(--biscuit-splitview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff)))); outline-offset: -2px; }
  ::slotted(*) { min-width: 0; }
`;
	var navBarViewStyles = `
  :host {
    box-sizing: border-box; display: block; min-width: 0; color-scheme: inherit;
    border: var(--biscuit-navbarview-border, 0);
    border-bottom: var(--biscuit-navbarview-bottom-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-navbarview-radius, 0);
    background: var(--biscuit-navbarview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-navbarview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-navbarview-font, 550 0.875rem/1.4 system-ui, sans-serif);
  }
  ${themeStyles}
  :host([sticky]) { position: sticky; top: var(--biscuit-navbarview-sticky-top, 0px); z-index: var(--biscuit-navbarview-sticky-z-index, 20); }
  :host(:not([contained])), :host([full-width]) { width: 100%; border-right: 0; border-left: 0; border-radius: 0; }
  [hidden] { display: none !important; }
  [part="nav"] { display: flex; align-items: center; gap: calc(1rem * var(--biscuit-space-scale, 1)); min-width: 0; min-height: 3.5rem; padding: var(--biscuit-navbarview-padding, calc(0.55rem * var(--biscuit-space-scale, 1)) calc(0.75rem * var(--biscuit-space-scale, 1))); }
  [part="brand"] { flex: none; color: inherit; font-size: 1rem; font-weight: 750; text-decoration: none; white-space: nowrap; }
  [part="menu"] { display: flex; flex: 1; align-items: center; justify-content: space-between; gap: 0.75rem; min-width: 0; }
  [part="links"], [part="actions"] { display: flex; align-items: center; gap: 0.25rem; min-width: 0; }
  [part="links"] { flex-wrap: wrap; }
  ::slotted(a) {
    display: inline-flex; align-items: center; min-height: 2.25rem; padding: 0.4rem 0.65rem;
    border-radius: 0.45rem; color: inherit; text-decoration: none; white-space: nowrap;
  }
  ::slotted(a:hover), ::slotted(a[aria-current="page"]) {
    background: var(--biscuit-navbarview-active-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f)));
    color: var(--biscuit-navbarview-active-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff)));
  }
  ::slotted(a[slot="actions"]) {
    border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    font-weight: 650;
  }
  [part="toggle"] {
    flex: none; align-items: center; justify-content: center; gap: 0.4rem; min-height: 2.25rem;
    padding: 0.4rem 0.65rem; border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    border-radius: 0.45rem; background: transparent; color: inherit; font: inherit; cursor: pointer;
  }
  [part="brand"]:focus-visible, [part="toggle"]:focus-visible, ::slotted(a:focus-visible) {
    outline: 2px solid var(--biscuit-navbarview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: 2px;
  }
  :host([data-mobile]) [part="nav"] { flex-wrap: wrap; }
  :host([data-mobile]) [part="brand"] { flex: 1; }
  :host([data-mobile]) [part="toggle"] { display: inline-flex; }
  :host([data-mobile]) [part="menu"] { flex: 0 0 100%; flex-direction: column; align-items: stretch; gap: 0.5rem; padding-top: 0.65rem; border-top: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))); }
  :host([data-mobile]) [part="links"], :host([data-mobile]) [part="actions"] { flex-direction: column; align-items: stretch; }
  :host([data-mobile]) ::slotted(a) { width: 100%; }
`;
	var footerViewStyles = `
  :host {
    box-sizing: border-box; display: block; width: 100%; color-scheme: inherit;
    border: var(--biscuit-footerview-border, 0);
    border-top: var(--biscuit-footerview-top-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-footerview-radius, 0);
    background: var(--biscuit-footerview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-footerview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-footerview-font, 400 0.9375rem/1.5 system-ui, sans-serif);
  }
  ${themeStyles}
  :host([contained]:not([full-width])) { border: var(--biscuit-footerview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)))); border-radius: var(--biscuit-footerview-radius, .75rem); }
  [hidden] { display: none !important; }
  [part="footer"] { box-sizing: border-box; width: 100%; }
  [part="inner"] { box-sizing: border-box; max-width: var(--biscuit-footerview-max-width, 80rem); margin-inline: auto; padding: var(--biscuit-footerview-padding, calc(2.5rem * var(--biscuit-space-scale, 1)) calc(1.25rem * var(--biscuit-space-scale, 1))); }
  [part="main"] { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 2fr); gap: calc(2rem * var(--biscuit-space-scale, 1)); }
  :host(:not([data-has-groups])) [part="main"] { grid-template-columns: minmax(0, 1fr); }
  [part="intro"] { display: grid; align-content: start; gap: calc(.75rem * var(--biscuit-space-scale, 1)); min-width: 0; }
  [part="brand"] { font-size: 1.1rem; font-weight: 750; }
  [part="brand"] a { color: inherit; text-decoration: none; }
  [part="description"] { max-width: 38ch; color: var(--biscuit-footerview-muted-color, color-mix(in srgb, currentColor 72%, transparent)); }
  [part="description"] p { margin: 0; }
  [part="actions"] { display: flex; flex-wrap: wrap; align-items: center; gap: calc(.6rem * var(--biscuit-space-scale, 1)); }
  slot[name="actions"]::slotted(a) { display: inline-flex; align-items: center; min-height: 2.25rem; padding: .4rem .7rem; border: 1px solid currentColor; border-radius: .45rem; color: inherit; font-weight: 650; text-decoration: none; }
  slot[name="actions"]::slotted(a:hover) { background: color-mix(in srgb, currentColor 10%, transparent); }
  slot[name="actions"]::slotted(a:focus-visible) { outline: 2px solid var(--biscuit-footerview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff)))); outline-offset: 3px; }
  [part="groups"] { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, 9rem), 1fr)); gap: calc(1.5rem * var(--biscuit-space-scale, 1)); min-width: 0; }
  [part="group"] { min-width: 0; }
  [part="group-heading"] { display: block; margin: 0 0 calc(.55rem * var(--biscuit-space-scale, 1)); font: inherit; font-weight: 700; }
  [part="group-links"] { display: grid; justify-items: start; gap: calc(.3rem * var(--biscuit-space-scale, 1)); }
  [part="group-links"]::slotted(a) { color: inherit; text-decoration: none; border-radius: .25rem; }
  [part="group-links"]::slotted(a:hover) { text-decoration: underline; }
  [part="group-links"]::slotted(a:focus-visible), [part="brand"] a:focus-visible { outline: 2px solid var(--biscuit-footerview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff)))); outline-offset: 3px; }
  [part="bottom"] { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: calc(.75rem * var(--biscuit-space-scale, 1)); margin-top: calc(2rem * var(--biscuit-space-scale, 1)); padding-top: calc(1rem * var(--biscuit-space-scale, 1)); border-top: 1px solid var(--biscuit-footerview-divider-color, var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)))); font-size: .875rem; color: var(--biscuit-footerview-muted-color, color-mix(in srgb, currentColor 72%, transparent)); }
  @media (max-width: 640px) { [part="main"] { grid-template-columns: minmax(0, 1fr); } [part="groups"] { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
  @media (max-width: 420px) { [part="groups"] { grid-template-columns: minmax(0, 1fr); } }
`;
	var sidebarViewStyles = `
  :host {
    box-sizing: border-box;
    display: block;
    width: var(--biscuit-sidebarview-width, 16rem);
    max-width: 100%;
    min-width: 0;
    color-scheme: inherit;
    font: var(--biscuit-sidebarview-font, 400 0.9375rem/1.45 system-ui, sans-serif);
  }

  ${themeStyles}

  :host([sticky]:not([data-mobile])) { position: sticky; top: var(--biscuit-sidebarview-sticky-top, 0px); align-self: start; }
  :host([sticky]:not([data-mobile])) [part="panel"] { height: calc(100dvh - var(--biscuit-sidebarview-sticky-top, 0px)); overflow-y: auto; scrollbar-width: thin; }
  [hidden] { display: none !important; }
  [part="toggle"] {
    align-items: center;
    gap: 0.55rem;
    min-height: 2.75rem;
    padding: 0.55rem 0.8rem;
    border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    border-radius: 0.5rem;
    background: var(--biscuit-sidebarview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-sidebarview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: inherit;
    font-weight: 650;
    cursor: pointer;
  }
  [part="toggle"]:focus-visible,
  [part="close"]:focus-visible,
  [part="link"]:focus-visible,
  [part="category-link"]:focus-visible,
  ::slotted(a:focus-visible) {
    outline: 2px solid var(--biscuit-sidebarview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: 2px;
  }
  [part="toggle-icon"] { font-size: 1.2em; line-height: 1; }
  [part="backdrop"] {
    position: fixed;
    inset: 0;
    z-index: 1000;
    background: var(--biscuit-sidebarview-backdrop, rgb(0 0 0 / 55%));
  }
  [part="panel"] {
    box-sizing: border-box;
    width: 100%;
    min-height: 12rem;
    padding: var(--biscuit-sidebarview-padding, calc(0.9rem * var(--biscuit-space-scale, 1)));
    border: var(--biscuit-sidebarview-border, 0);
    border-right: var(--biscuit-sidebarview-divider, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-sidebarview-radius, 0);
    background: var(--biscuit-sidebarview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    box-shadow: var(--biscuit-sidebarview-shadow, none);
    color: var(--biscuit-sidebarview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
  }
  [part="header"] {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    margin-bottom: 0.75rem;
    padding: 0.2rem 0.35rem 0.8rem;
    border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
  }
  [part="title"] { font-size: 1rem; font-weight: 750; }
  [part="close"] {
    width: 2rem;
    height: 2rem;
    border: 0;
    border-radius: 0.4rem;
    background: transparent;
    color: inherit;
    font: inherit;
    font-size: 1.25rem;
    cursor: pointer;
  }
  [part="close"]:hover { background: var(--biscuit-sidebarview-hover-background, light-dark(#f2f0ff, #373947)); }
  [part="body"], [part="items"] { display: grid; gap: 0.25rem; }
  [part="category"] { display: grid; gap: 0.15rem; margin-top: 0.55rem; }
  [part="category"]:first-child { margin-top: 0; }
  [part="category-items"] { display: grid; gap: 0.15rem; margin-left: 0.55rem; padding-left: 0.55rem; border-left: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))); }
  [part="category-label"], [part="category-link"] { display: block; padding: 0.5rem 0.65rem 0.3rem; font-size: 0.75rem; font-weight: 750; letter-spacing: 0.055em; text-transform: uppercase; color: inherit; text-decoration: none; }
  [part="category"][data-active] > [part="category-label"],
  [part="category"][data-active] > [part="category-link"]:not([aria-current]):not([data-tracked]),
  [part="category"][data-current] > [part="category-label"],
  [part="category"][data-current] > [part="category-link"]:not([aria-current]):not([data-tracked]) { color: var(--biscuit-sidebarview-category-active-color, inherit); }
  [part="link"] { display: block; padding: calc(0.48rem * var(--biscuit-space-scale, 1)) calc(0.65rem * var(--biscuit-space-scale, 1)); border-radius: 0.45rem; color: inherit; text-decoration: none; }
  ::slotted(a) {
    display: block;
    padding: 0.55rem 0.75rem;
    border-radius: 0.45rem;
    color: inherit;
    text-decoration: none;
  }
  ::slotted(a:hover), ::slotted(a[aria-current="page"]), ::slotted(a[aria-current="location"]), ::slotted(a[data-tracked]),
  [part="link"]:hover, [part="link"][aria-current], [part="link"][data-tracked],
  [part="category-link"][aria-current], [part="category-link"][data-tracked] {
    background: var(--biscuit-sidebarview-active-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f)));
    color: var(--biscuit-sidebarview-active-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff)));
  }
  :host([track-scroll-behavior="smooth"]) ::slotted(a),
  :host([track-scroll-behavior="smooth"]) [part="link"],
  :host([track-scroll-behavior="smooth"]) [part="category-link"] {
    transition: background-color var(--biscuit-sidebarview-selection-fade-duration, 110ms) ease-out,
      color var(--biscuit-sidebarview-selection-fade-duration, 110ms) ease-out;
  }
  :host([track-scroll-behavior="smooth"]) ::slotted(a[data-tracked]),
  :host([track-scroll-behavior="smooth"]) [part="link"][data-tracked],
  :host([track-scroll-behavior="smooth"]) [part="category-link"][data-tracked] {
    transition-duration: 0ms;
  }
  :host([data-mobile]) { width: auto; }
  :host([data-mobile]) [part="toggle"] { display: inline-flex; }
  :host([data-mobile]) [part="panel"] {
    position: fixed;
    z-index: 1001;
    top: 0;
    bottom: 0;
    left: 0;
    width: min(var(--biscuit-sidebarview-mobile-width, 18rem), calc(100vw - 3rem));
    min-height: 0;
    overflow-y: auto;
    overscroll-behavior: contain;
    border-radius: 0 var(--biscuit-sidebarview-radius, 0) var(--biscuit-sidebarview-radius, 0) 0;
    box-shadow: var(--biscuit-sidebarview-mobile-shadow, 0 12px 32px rgb(0 0 0 / 25%));
    visibility: hidden;
    transform: translateX(-100%);
    transition: transform 180ms ease, visibility 180ms ease;
  }
  :host([data-mobile][open]) [part="panel"] { visibility: visible; transform: none; }
  :host([data-mobile][side="right"]) [part="panel"] {
    right: 0;
    left: auto;
    border-radius: var(--biscuit-sidebarview-radius, 0) 0 0 var(--biscuit-sidebarview-radius, 0);
    transform: translateX(100%);
  }
  :host([data-mobile][side="right"][open]) [part="panel"] { transform: none; }
  @media (prefers-reduced-motion: reduce) {
    :host([data-mobile]) [part="panel"] { transition: none; }
    :host([track-scroll-behavior="smooth"]) ::slotted(a),
    :host([track-scroll-behavior="smooth"]) [part="link"],
    :host([track-scroll-behavior="smooth"]) [part="category-link"] { transition: none; }
  }
`;
	var BiscuitSplitView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"orientation",
				"resizable",
				"value",
				"min",
				"max",
				"left-min",
				"left-max",
				"right-min",
				"right-max",
				"top-min",
				"top-max",
				"bottom-min",
				"bottom-max",
				"aria-label"
			];
		}
		constructor() {
			super();
			this._dragging = null;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = splitViewStyles;
			this._layout = document.createElement("div");
			this._layout.setAttribute("part", "layout");
			this._firstPane = document.createElement("div");
			this._firstPane.setAttribute("part", "pane first");
			this._firstPane.id = "biscuit-split-first-" + ++splitViewId;
			const firstSlot = document.createElement("slot");
			firstSlot.name = "first";
			this._firstPane.append(firstSlot);
			this._handle = document.createElement("div");
			this._handle.setAttribute("part", "handle");
			this._handle.setAttribute("role", "separator");
			this._handle.setAttribute("aria-controls", this._firstPane.id);
			this._handle.addEventListener("keydown", (event) => this._onKeyDown(event));
			this._handle.addEventListener("pointerdown", (event) => this._onPointerDown(event));
			this._handle.addEventListener("pointermove", (event) => this._onPointerMove(event));
			this._handle.addEventListener("pointerup", (event) => this._onPointerUp(event));
			this._handle.addEventListener("pointercancel", (event) => this._onPointerCancel(event));
			this._handle.addEventListener("lostpointercapture", () => this._finishDrag());
			this._secondPane = document.createElement("div");
			this._secondPane.setAttribute("part", "pane second");
			const secondSlot = document.createElement("slot");
			secondSlot.name = "second";
			this._secondPane.append(secondSlot);
			this._layout.append(this._firstPane, this._handle, this._secondPane);
			root.append(style, this._layout);
			this._syncState();
		}
		disconnectedCallback() {
			this._dragging = null;
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue !== newValue) this._syncState();
		}
		get orientation() {
			return this.getAttribute("orientation") === "vertical" ? "vertical" : "horizontal";
		}
		set orientation(value) {
			if (value === "vertical") this.setAttribute("orientation", "vertical");
			else if (value === "horizontal") this.removeAttribute("orientation");
			else throw new TypeError("SplitView orientation must be \"horizontal\" or \"vertical\"");
		}
		get resizable() {
			return this.hasAttribute("resizable");
		}
		set resizable(value) {
			this.toggleAttribute("resizable", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		_bound(name, fallback) {
			const raw = this.getAttribute(name);
			const number = raw == null ? fallback : Number(raw);
			return Number.isFinite(number) ? Math.max(0, Math.min(100, number)) : fallback;
		}
		get min() {
			return this._bound("min", 10);
		}
		set min(value) {
			this.setAttribute("min", String(value));
		}
		get max() {
			return Math.max(this.min, this._bound("max", 90));
		}
		set max(value) {
			this.setAttribute("max", String(value));
		}
		get leftMin() {
			return this._bound("left-min", this.min);
		}
		set leftMin(value) {
			this.setAttribute("left-min", String(value));
		}
		get leftMax() {
			return Math.max(this.leftMin, this._bound("left-max", this.max));
		}
		set leftMax(value) {
			this.setAttribute("left-max", String(value));
		}
		get rightMin() {
			return this._bound("right-min", this.min);
		}
		set rightMin(value) {
			this.setAttribute("right-min", String(value));
		}
		get rightMax() {
			return Math.max(this.rightMin, this._bound("right-max", this.max));
		}
		set rightMax(value) {
			this.setAttribute("right-max", String(value));
		}
		get topMin() {
			return this._bound("top-min", this.min);
		}
		set topMin(value) {
			this.setAttribute("top-min", String(value));
		}
		get topMax() {
			return Math.max(this.topMin, this._bound("top-max", this.max));
		}
		set topMax(value) {
			this.setAttribute("top-max", String(value));
		}
		get bottomMin() {
			return this._bound("bottom-min", this.min);
		}
		set bottomMin(value) {
			this.setAttribute("bottom-min", String(value));
		}
		get bottomMax() {
			return Math.max(this.bottomMin, this._bound("bottom-max", this.max));
		}
		set bottomMax(value) {
			this.setAttribute("bottom-max", String(value));
		}
		get _effectiveBounds() {
			const firstMin = this.orientation === "horizontal" ? this.leftMin : this.topMin;
			const firstMax = this.orientation === "horizontal" ? this.leftMax : this.topMax;
			const secondMin = this.orientation === "horizontal" ? this.rightMin : this.bottomMin;
			const secondMax = this.orientation === "horizontal" ? this.rightMax : this.bottomMax;
			const min = Math.max(firstMin, 100 - secondMax);
			return {
				min,
				max: Math.max(min, Math.min(firstMax, 100 - secondMin))
			};
		}
		get value() {
			const raw = this.getAttribute("value");
			const number = raw == null ? 50 : Number(raw);
			const { min, max } = this._effectiveBounds;
			return Math.max(min, Math.min(max, Number.isFinite(number) ? number : 50));
		}
		set value(value) {
			const number = Number(value);
			if (!Number.isFinite(number)) throw new TypeError("SplitView value must be a finite percentage");
			const { min, max } = this._effectiveBounds;
			this.setAttribute("value", String(Math.max(min, Math.min(max, number))));
		}
		_syncState() {
			if (!this._layout) return;
			this._layout.style.setProperty("--_split-first", this.value + "fr");
			this._layout.style.setProperty("--_split-second", 100 - this.value + "fr");
			this._handle.hidden = !this.resizable;
			this._handle.tabIndex = this.resizable ? 0 : -1;
			this._handle.setAttribute("aria-label", this.getAttribute("aria-label") || "Resize panels");
			this._handle.setAttribute("aria-orientation", this.orientation === "horizontal" ? "vertical" : "horizontal");
			this._handle.setAttribute("aria-valuemin", String(this._effectiveBounds.min));
			this._handle.setAttribute("aria-valuemax", String(this._effectiveBounds.max));
			this._handle.setAttribute("aria-valuenow", String(this.value));
		}
		_setUserValue(next) {
			const before = this.value;
			this.value = next;
			if (this.value === before) return;
			this.dispatchEvent(new Event("input", {
				bubbles: true,
				composed: true
			}));
		}
		_onKeyDown(event) {
			if (!this.resizable) return;
			const step = event.shiftKey ? 10 : 5;
			let next;
			if (event.key === "Home") next = this._effectiveBounds.min;
			else if (event.key === "End") next = this._effectiveBounds.max;
			else if (this.orientation === "horizontal" && event.key === "ArrowLeft") next = this.value - step;
			else if (this.orientation === "horizontal" && event.key === "ArrowRight") next = this.value + step;
			else if (this.orientation === "vertical" && event.key === "ArrowUp") next = this.value - step;
			else if (this.orientation === "vertical" && event.key === "ArrowDown") next = this.value + step;
			else return;
			event.preventDefault();
			const before = this.value;
			this._setUserValue(next);
			if (this.value !== before) this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			}));
		}
		_onPointerDown(event) {
			if (!this.resizable || event.button != null && event.button !== 0) return;
			event.preventDefault();
			this._dragging = {
				pointerId: event.pointerId,
				startValue: this.value
			};
			this._handle.focus();
			this._handle.setPointerCapture?.(event.pointerId);
		}
		_onPointerMove(event) {
			if (!this._dragging || event.pointerId !== this._dragging.pointerId) return;
			const rect = this.getBoundingClientRect();
			const handleRect = this._handle.getBoundingClientRect();
			const horizontal = this.orientation === "horizontal";
			const handleSize = horizontal ? handleRect.width : handleRect.height;
			const length = (horizontal ? rect.width : rect.height) - handleSize;
			if (length <= 0) return;
			const position = (horizontal ? event.clientX - rect.left : event.clientY - rect.top) - handleSize / 2;
			this._setUserValue(Math.round(position / length * 1e3) / 10);
		}
		_onPointerUp(event) {
			if (!this._dragging || event.pointerId !== this._dragging.pointerId) return;
			this._finishDrag();
			this._handle.releasePointerCapture?.(event.pointerId);
		}
		_onPointerCancel(event) {
			if (!this._dragging || event.pointerId !== this._dragging.pointerId) return;
			this._dragging = null;
			this._handle.releasePointerCapture?.(event.pointerId);
		}
		_finishDrag() {
			if (!this._dragging) return;
			const changed = this.value !== this._dragging.startValue;
			this._dragging = null;
			if (changed) this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			}));
		}
	};
	var BiscuitTabLayout = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["value", "aria-label"];
		}
		constructor() {
			super();
			this._id = ++tabLayoutId;
			this._tabs = [];
			this._buttons = [];
			this._panels = [];
			this._value = "";
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = tabLayoutStyles;
			const layout = document.createElement("div");
			layout.setAttribute("part", "layout");
			this._tablist = document.createElement("div");
			this._tablist.setAttribute("part", "tablist");
			this._tablist.setAttribute("role", "tablist");
			this._panelsContainer = document.createElement("div");
			this._panelsContainer.setAttribute("part", "panels");
			layout.append(this._tablist, this._panelsContainer);
			root.append(style, layout);
			this._syncLabel();
		}
		connectedCallback() {
			this._syncTabs();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver((mutations) => {
					if (mutations.some((mutation) => mutation.type === "childList" && mutation.target === this || mutation.type === "attributes" && mutation.target.parentElement === this)) this._syncTabs();
				});
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					attributes: true,
					attributeFilter: [
						"value",
						"label",
						"disabled",
						"selected"
					]
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._observer = null;
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue === newValue) return;
			if (name === "value") this._applySelection();
			else if (name === "aria-label") this._syncLabel();
		}
		get value() {
			return this._value;
		}
		set value(value) {
			this.setAttribute("value", String(value ?? ""));
		}
		get orientation() {
			return this.getAttribute("orientation") === "vertical" ? "vertical" : "horizontal";
		}
		set orientation(value) {
			if (value === "vertical") this.setAttribute("orientation", "vertical");
			else this.removeAttribute("orientation");
		}
		get variant() {
			return this.getAttribute("variant") === "pills" ? "pills" : "underline";
		}
		set variant(value) {
			if (value === "pills") this.setAttribute("variant", "pills");
			else this.removeAttribute("variant");
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		_syncLabel() {
			const label = this.getAttribute("aria-label");
			if (label) this._tablist.setAttribute("aria-label", label);
			else this._tablist.removeAttribute("aria-label");
		}
		_syncTabs() {
			this._tabs = Array.from(this.children).filter((child) => child.tagName === "BISCUIT-TAB").map((child, index) => ({
				element: child,
				value: child.getAttribute("value") ?? `tab-${index + 1}`,
				label: child.getAttribute("label") ?? `Tab ${index + 1}`,
				disabled: child.hasAttribute("disabled"),
				selected: child.hasAttribute("selected")
			}));
			this._buttons = this._tabs.map((tab, index) => {
				tab.element.setAttribute("slot", `biscuit-tab-${this._id}-${index}`);
				const button = document.createElement("button");
				button.type = "button";
				button.setAttribute("part", "tab");
				button.setAttribute("role", "tab");
				button.id = `biscuit-tab-${this._id}-${index}`;
				button.setAttribute("aria-controls", `biscuit-panel-${this._id}-${index}`);
				button.textContent = tab.label;
				button.disabled = tab.disabled;
				button.addEventListener("click", () => this._selectIndex(index));
				button.addEventListener("keydown", (event) => this._onTabKeyDown(event, index));
				return button;
			});
			this._panels = this._tabs.map((tab, index) => {
				const panel = document.createElement("div");
				panel.setAttribute("part", "tabpanel");
				panel.setAttribute("role", "tabpanel");
				panel.id = `biscuit-panel-${this._id}-${index}`;
				panel.setAttribute("aria-labelledby", `biscuit-tab-${this._id}-${index}`);
				panel.tabIndex = 0;
				const slot = document.createElement("slot");
				slot.name = `biscuit-tab-${this._id}-${index}`;
				panel.append(slot);
				return panel;
			});
			this._tablist.replaceChildren(...this._buttons);
			this._panelsContainer.replaceChildren(...this._panels);
			this._applySelection();
		}
		_applySelection() {
			const requested = this.getAttribute("value");
			let selectedIndex = requested === null ? this._tabs.findIndex((tab) => tab.selected && !tab.disabled) : this._tabs.findIndex((tab) => tab.value === requested && !tab.disabled);
			if (selectedIndex < 0) selectedIndex = this._tabs.findIndex((tab) => !tab.disabled);
			this._value = selectedIndex < 0 ? "" : this._tabs[selectedIndex].value;
			this._buttons.forEach((button, index) => {
				const selected = index === selectedIndex;
				button.setAttribute("aria-selected", String(selected));
				button.tabIndex = selected || selectedIndex < 0 && index === 0 ? 0 : -1;
				this._panels[index].hidden = !selected;
			});
		}
		_selectIndex(index) {
			const tab = this._tabs[index];
			if (!tab || tab.disabled) return;
			const previous = this._value;
			this.value = tab.value;
			this._buttons[index].focus();
			if (this._value !== previous) this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			}));
		}
		_onTabKeyDown(event, index) {
			const horizontal = this.orientation === "horizontal";
			const direction = horizontal && event.key === "ArrowRight" || !horizontal && event.key === "ArrowDown" ? 1 : horizontal && event.key === "ArrowLeft" || !horizontal && event.key === "ArrowUp" ? -1 : 0;
			if (!direction && event.key !== "Home" && event.key !== "End") return;
			event.preventDefault();
			const enabled = this._tabs.flatMap((tab, tabIndex) => tab.disabled ? [] : [tabIndex]);
			if (!enabled.length) return;
			let next;
			if (event.key === "Home") next = enabled[0];
			else if (event.key === "End") next = enabled[enabled.length - 1];
			else next = enabled[(enabled.indexOf(index) + direction + enabled.length) % enabled.length];
			this._selectIndex(next);
		}
	};
	var BiscuitNavBarView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"brand",
				"brand-href",
				"mode",
				"open",
				"aria-label"
			];
		}
		constructor() {
			super();
			this._isMobile = false;
			this._connected = false;
			this._onMediaChange = () => this._syncMode();
			this._onDocumentKeyDown = (event) => {
				if (event.key === "Escape" && this._isMobile && this.open) {
					event.preventDefault();
					this._setOpen(false);
					this._toggle.focus();
				}
			};
			this._onDocumentPointerDown = (event) => {
				if (!event.composedPath?.().includes(this)) this._setOpen(false);
			};
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = navBarViewStyles;
			this._nav = document.createElement("nav");
			this._nav.setAttribute("part", "nav");
			this._brandLink = document.createElement("a");
			this._brandLink.setAttribute("part", "brand");
			const brandSlot = document.createElement("slot");
			brandSlot.name = "brand";
			this._brandText = document.createElement("span");
			brandSlot.append(this._brandText);
			this._brandLink.append(brandSlot);
			this._toggle = document.createElement("button");
			this._toggle.type = "button";
			this._toggle.setAttribute("part", "toggle");
			const icon = document.createElement("span");
			icon.setAttribute("aria-hidden", "true");
			icon.textContent = "☰";
			const toggleText = document.createElement("span");
			toggleText.textContent = "Menu";
			this._toggle.append(icon, toggleText);
			this._toggle.addEventListener("click", () => this._setOpen(!this.open));
			this._menu = document.createElement("div");
			this._menu.setAttribute("part", "menu");
			this._menu.id = `biscuit-navbar-menu-${++navBarViewId}`;
			this._toggle.setAttribute("aria-controls", this._menu.id);
			this._links = document.createElement("div");
			this._links.setAttribute("part", "links");
			this._links.append(document.createElement("slot"));
			this._actions = document.createElement("div");
			this._actions.setAttribute("part", "actions");
			const actionsSlot = document.createElement("slot");
			actionsSlot.name = "actions";
			actionsSlot.addEventListener("slotchange", () => this._syncActions());
			this._actions.append(actionsSlot);
			this._menu.append(this._links, this._actions);
			this._menu.addEventListener("click", (event) => {
				if (!this._isMobile) return;
				if (event.composedPath?.().some((node) => node.tagName === "A" && node.hasAttribute("href"))) this._setOpen(false);
			});
			this._nav.append(this._brandLink, this._toggle, this._menu);
			root.append(style, this._nav);
			this._renderState();
		}
		connectedCallback() {
			this._connected = true;
			this._media = globalThis.matchMedia?.("(max-width: 720px)");
			this._media?.addEventListener?.("change", this._onMediaChange);
			this._syncActions();
			this._syncMode();
		}
		disconnectedCallback() {
			this._connected = false;
			this._media?.removeEventListener?.("change", this._onMediaChange);
			this._media = null;
			document.removeEventListener("keydown", this._onDocumentKeyDown);
			document.removeEventListener("pointerdown", this._onDocumentPointerDown);
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue === newValue || !this._nav) return;
			if (name === "mode") this._syncMode();
			else this._renderState();
		}
		get brand() {
			return this.getAttribute("brand") ?? "Biscuit";
		}
		set brand(value) {
			if (value == null) this.removeAttribute("brand");
			else this.setAttribute("brand", String(value));
		}
		get brandHref() {
			return this.getAttribute("brand-href") ?? "#";
		}
		set brandHref(value) {
			if (value == null) this.removeAttribute("brand-href");
			else this.setAttribute("brand-href", String(value));
		}
		get mode() {
			return this.getAttribute("mode") ?? "auto";
		}
		set mode(value) {
			if (value == null || value === "auto") this.removeAttribute("mode");
			else if (value === "mobile" || value === "desktop") this.setAttribute("mode", value);
			else throw new TypeError("NavBarView mode must be \"auto\", \"mobile\", or \"desktop\"");
		}
		get open() {
			return this.hasAttribute("open");
		}
		set open(value) {
			this.toggleAttribute("open", Boolean(value));
		}
		get sticky() {
			return this.hasAttribute("sticky");
		}
		set sticky(value) {
			this.toggleAttribute("sticky", Boolean(value));
		}
		get fullWidth() {
			return !this.hasAttribute("contained") || this.hasAttribute("full-width");
		}
		set fullWidth(value) {
			this.toggleAttribute("contained", !Boolean(value));
			this.toggleAttribute("full-width", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		toggle() {
			this._setOpen(!this.open);
		}
		_syncActions() {
			this._actions.hidden = !Array.from(this.children ?? []).some((child) => child.getAttribute?.("slot") === "actions");
		}
		_syncMode() {
			this._isMobile = this.mode === "mobile" || this.mode !== "desktop" && Boolean(this._media?.matches);
			this.toggleAttribute("data-mobile", this._isMobile);
			if (!this._isMobile && this.open) this.open = false;
			this._renderState();
		}
		_setOpen(open) {
			if (!this._isMobile || this.open === open) return;
			this.open = open;
			this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			}));
		}
		_renderState() {
			const label = this.getAttribute("aria-label") || "Primary navigation";
			this._nav.setAttribute("aria-label", label);
			this._brandText.textContent = this.brand;
			this._brandLink.setAttribute("href", this.brandHref);
			this._toggle.hidden = !this._isMobile;
			this._toggle.setAttribute("aria-label", `${this.open ? "Close" : "Open"} ${label} menu`);
			this._toggle.setAttribute("aria-expanded", String(this._isMobile && this.open));
			this._menu.hidden = this._isMobile && !this.open;
			this._menu.inert = this._isMobile && !this.open;
			document.removeEventListener("keydown", this._onDocumentKeyDown);
			document.removeEventListener("pointerdown", this._onDocumentPointerDown);
			if (this._connected && this._isMobile && this.open) {
				document.addEventListener("keydown", this._onDocumentKeyDown);
				document.addEventListener("pointerdown", this._onDocumentPointerDown);
			}
		}
	};
	var BiscuitFooterView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"brand",
				"brand-href",
				"description",
				"copyright",
				"aria-label"
			];
		}
		constructor() {
			super();
			this._id = ++footerViewId;
			this._groupData = [];
			this._generatedLinks = [];
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = footerViewStyles;
			this._footer = document.createElement("footer");
			this._footer.setAttribute("part", "footer");
			const inner = document.createElement("div");
			inner.setAttribute("part", "inner");
			const main = document.createElement("div");
			main.setAttribute("part", "main");
			const intro = document.createElement("div");
			intro.setAttribute("part", "intro");
			this._brandWrap = document.createElement("div");
			this._brandWrap.setAttribute("part", "brand");
			const brandSlot = document.createElement("slot");
			brandSlot.name = "brand";
			this._brandLink = document.createElement("a");
			this._brandText = document.createElement("strong");
			brandSlot.append(this._brandLink, this._brandText);
			this._brandWrap.append(brandSlot);
			this._descriptionWrap = document.createElement("div");
			this._descriptionWrap.setAttribute("part", "description");
			const descriptionSlot = document.createElement("slot");
			descriptionSlot.name = "description";
			this._descriptionText = document.createElement("p");
			descriptionSlot.append(this._descriptionText);
			this._descriptionWrap.append(descriptionSlot);
			this._actionsWrap = document.createElement("div");
			this._actionsWrap.setAttribute("part", "actions");
			const actionsSlot = document.createElement("slot");
			actionsSlot.name = "actions";
			this._actionsWrap.append(actionsSlot);
			intro.append(this._brandWrap, this._descriptionWrap, this._actionsWrap);
			this._groupsWrap = document.createElement("div");
			this._groupsWrap.setAttribute("part", "groups");
			main.append(intro, this._groupsWrap);
			this._bottomWrap = document.createElement("div");
			this._bottomWrap.setAttribute("part", "bottom");
			const bottomSlot = document.createElement("slot");
			bottomSlot.name = "bottom";
			this._copyrightText = document.createElement("span");
			bottomSlot.append(this._copyrightText);
			this._bottomWrap.append(bottomSlot);
			inner.append(main, this._bottomWrap);
			this._footer.append(inner);
			root.append(style, this._footer);
			this._syncContent();
		}
		connectedCallback() {
			this._syncGroups();
			this._syncContent();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver(() => {
					this._syncGroups();
					this._syncContent();
				});
				this._observer.observe(this, {
					childList: true,
					attributes: true,
					attributeFilter: ["data-footer-group", "slot"]
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._observer = null;
		}
		attributeChangedCallback() {
			if (this._footer) this._syncContent();
		}
		get brand() {
			return this.getAttribute("brand") ?? "";
		}
		set brand(value) {
			if (value == null) this.removeAttribute("brand");
			else this.setAttribute("brand", String(value));
		}
		get brandHref() {
			return this.getAttribute("brand-href") ?? "";
		}
		set brandHref(value) {
			if (value == null || value === "") this.removeAttribute("brand-href");
			else this.setAttribute("brand-href", String(value));
		}
		get description() {
			return this.getAttribute("description") ?? "";
		}
		set description(value) {
			if (value == null) this.removeAttribute("description");
			else this.setAttribute("description", String(value));
		}
		get copyright() {
			return this.getAttribute("copyright") ?? "";
		}
		set copyright(value) {
			if (value == null) this.removeAttribute("copyright");
			else this.setAttribute("copyright", String(value));
		}
		get fullWidth() {
			return !this.hasAttribute("contained") || this.hasAttribute("full-width");
		}
		set fullWidth(value) {
			this.toggleAttribute("contained", !Boolean(value));
			this.toggleAttribute("full-width", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		get groups() {
			return this._groupData.map((group) => ({
				...group,
				links: group.links.map((link) => ({ ...link }))
			}));
		}
		set groups(value) {
			if (!Array.isArray(value)) throw new TypeError("FooterView groups must be an array");
			const groups = value.map((group) => {
				if (!Array.isArray(group?.links)) throw new TypeError("Each FooterView group needs a links array");
				return {
					label: String(group.label ?? "Links"),
					links: group.links.map((link) => ({
						label: String(link.label ?? ""),
						href: String(link.href ?? "#"),
						target: link.target,
						rel: link.rel
					}))
				};
			});
			for (const link of this._generatedLinks) link.remove();
			this._generatedLinks = [];
			this._groupData = groups;
			for (const group of groups) for (const item of group.links) {
				const link = document.createElement("a");
				link.href = item.href;
				link.textContent = item.label;
				link.setAttribute("data-footer-group", group.label);
				if (item.target) link.target = String(item.target);
				if (item.rel) link.rel = String(item.rel);
				this.append(link);
				this._generatedLinks.push(link);
			}
			this._syncGroups();
		}
		_syncContent() {
			const slotted = (name) => Array.from(this.children ?? []).some((child) => child.getAttribute?.("slot") === name);
			const brand = this.brand;
			const brandHref = this.brandHref;
			this._brandLink.textContent = brand;
			this._brandText.textContent = brand;
			this._brandLink.hidden = !brandHref;
			this._brandText.hidden = Boolean(brandHref);
			if (brandHref) this._brandLink.setAttribute("href", brandHref);
			else this._brandLink.removeAttribute("href");
			this._descriptionText.textContent = this.description;
			this._copyrightText.textContent = this.copyright;
			this._brandWrap.hidden = !brand && !slotted("brand");
			this._descriptionWrap.hidden = !this.description && !slotted("description");
			this._actionsWrap.hidden = !slotted("actions");
			this._bottomWrap.hidden = !this.copyright && !slotted("bottom");
			if (this.hasAttribute("aria-label")) this._footer.setAttribute("aria-label", this.getAttribute("aria-label"));
			else this._footer.removeAttribute("aria-label");
		}
		_syncGroups() {
			const links = Array.from(this.children ?? []).filter((child) => child.tagName === "A" && (!child.hasAttribute("slot") || child.getAttribute("slot").startsWith("footer-links-")));
			const groups = /* @__PURE__ */ new Map();
			for (const link of links) {
				const label = link.getAttribute("data-footer-group")?.trim() || "Links";
				if (!groups.has(label)) groups.set(label, []);
				groups.get(label).push(link);
			}
			this._groupsWrap.replaceChildren();
			let index = 0;
			for (const [label, groupLinks] of groups) {
				const nav = document.createElement("nav");
				nav.setAttribute("part", "group");
				const heading = document.createElement("strong");
				heading.setAttribute("part", "group-heading");
				heading.id = `biscuit-footer-group-${this._id}-${index}`;
				heading.textContent = label;
				nav.setAttribute("aria-labelledby", heading.id);
				const slot = document.createElement("slot");
				slot.name = `footer-links-${index}`;
				slot.setAttribute("part", "group-links");
				for (const link of groupLinks) if (link.getAttribute("slot") !== slot.name) link.setAttribute("slot", slot.name);
				nav.append(heading, slot);
				this._groupsWrap.append(nav);
				index++;
			}
			this._groupsWrap.hidden = groups.size === 0;
			this.toggleAttribute("data-has-groups", groups.size > 0);
		}
	};
	var BiscuitSidebarView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"open",
				"mode",
				"label",
				"track-scroll",
				"track-scroll-behavior",
				"track-scroll-position",
				"track-scroll-offset"
			];
		}
		constructor() {
			super();
			this._isMobile = false;
			this._itemsData = null;
			this._scrollContainer = null;
			this._activeSectionLink = null;
			this._activeSectionHref = null;
			this._trackingScrollFrame = null;
			this._onMediaChange = () => this._syncMode();
			this._onDocumentKeyDown = (event) => this._handleKeyDown(event);
			this._onTrackingScroll = () => this._scheduleTrackingUpdate();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = sidebarViewStyles;
			this._toggle = document.createElement("button");
			this._toggle.type = "button";
			this._toggle.setAttribute("part", "toggle");
			this._toggle.setAttribute("aria-expanded", "false");
			this._toggle.addEventListener("click", () => this.toggle());
			const toggleIcon = document.createElement("span");
			toggleIcon.setAttribute("part", "toggle-icon");
			toggleIcon.setAttribute("aria-hidden", "true");
			toggleIcon.textContent = "☰";
			const toggleText = document.createElement("span");
			toggleText.textContent = "Menu";
			this._toggle.append(toggleIcon, toggleText);
			this._backdrop = document.createElement("div");
			this._backdrop.setAttribute("part", "backdrop");
			this._backdrop.setAttribute("aria-hidden", "true");
			this._backdrop.addEventListener("click", () => this._setOpen(false));
			this._panel = document.createElement("div");
			this._panel.setAttribute("part", "panel");
			this._panel.id = `biscuit-sidebar-${++sidebarViewId}`;
			this._panel.tabIndex = -1;
			this._toggle.setAttribute("aria-controls", this._panel.id);
			this._panel.addEventListener("click", (event) => {
				if (!this._isMobile) return;
				if (event.composedPath?.().find((node) => node.tagName === "A" && node.hasAttribute("href"))) this._setOpen(false, false);
			});
			const header = document.createElement("div");
			header.setAttribute("part", "header");
			this._title = document.createElement("strong");
			this._title.setAttribute("part", "title");
			this._close = document.createElement("button");
			this._close.type = "button";
			this._close.setAttribute("part", "close");
			this._close.textContent = "×";
			this._close.addEventListener("click", () => this._setOpen(false));
			header.append(this._title, this._close);
			const body = document.createElement("div");
			body.setAttribute("part", "body");
			this._slot = document.createElement("slot");
			this._items = document.createElement("div");
			this._items.setAttribute("part", "items");
			this._items.hidden = true;
			body.append(this._slot, this._items);
			this._panel.append(header, body);
			root.append(style, this._toggle, this._backdrop, this._panel);
			this._renderState();
		}
		connectedCallback() {
			this._media = globalThis.matchMedia?.("(max-width: 720px)");
			this._media?.addEventListener?.("change", this._onMediaChange);
			this._syncMode();
			this._renderItems();
			if (this.ownerDocument && globalThis.MutationObserver) {
				this._sourceObserver = new MutationObserver(() => {
					if (this._itemsData === null) this._renderItems();
					this.refreshTracking();
				});
				this._sourceObserver.observe(this, {
					childList: true,
					subtree: true,
					attributes: true,
					attributeFilter: [
						"href",
						"label",
						"aria-current"
					]
				});
			}
			this._syncTracking();
		}
		disconnectedCallback() {
			this._media?.removeEventListener?.("change", this._onMediaChange);
			this._media = null;
			this._sourceObserver?.disconnect();
			this._sourceObserver = null;
			this._stopTracking();
			document.removeEventListener("keydown", this._onDocumentKeyDown);
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue === newValue || !this._panel) return;
			if (name === "track-scroll") this._syncTracking();
			else if (name === "track-scroll-offset") this.refreshTracking();
			else if (name === "track-scroll-behavior" || name === "track-scroll-position") {
				if (this._activeSectionLink) this._revealTrackedLink(this._activeSectionLink);
			} else if (name === "mode") this._syncMode();
			else this._renderState();
		}
		get open() {
			return this.hasAttribute("open");
		}
		set open(value) {
			this.toggleAttribute("open", Boolean(value));
		}
		get sticky() {
			return this.hasAttribute("sticky");
		}
		set sticky(value) {
			this.toggleAttribute("sticky", Boolean(value));
		}
		get trackScroll() {
			return this.hasAttribute("track-scroll");
		}
		set trackScroll(value) {
			this.toggleAttribute("track-scroll", Boolean(value));
		}
		get trackScrollBehavior() {
			return this.getAttribute("track-scroll-behavior") === "smooth" ? "smooth" : "auto";
		}
		set trackScrollBehavior(value) {
			if (value == null || value === "auto") this.removeAttribute("track-scroll-behavior");
			else if (value === "smooth") this.setAttribute("track-scroll-behavior", value);
			else throw new TypeError("trackScrollBehavior must be \"auto\" or \"smooth\"");
		}
		get trackScrollPosition() {
			const value = this.getAttribute("track-scroll-position");
			return [
				"start",
				"center",
				"end"
			].includes(value) ? value : "nearest";
		}
		set trackScrollPosition(value) {
			if (value == null || value === "nearest") this.removeAttribute("track-scroll-position");
			else if ([
				"start",
				"center",
				"end"
			].includes(value)) this.setAttribute("track-scroll-position", value);
			else throw new TypeError("trackScrollPosition must be \"nearest\", \"start\", \"center\", or \"end\"");
		}
		get trackScrollOffset() {
			const value = this.getAttribute("track-scroll-offset");
			return value == null ? null : Math.max(0, Number(value) || 0);
		}
		set trackScrollOffset(value) {
			if (value == null) this.removeAttribute("track-scroll-offset");
			else if (Number.isFinite(Number(value)) && Number(value) >= 0) this.setAttribute("track-scroll-offset", String(value));
			else throw new TypeError("trackScrollOffset must be a non-negative number or null");
		}
		get scrollContainer() {
			return this._scrollContainer;
		}
		set scrollContainer(value) {
			this._scrollContainer = value ?? null;
			this._syncTracking();
		}
		get activeSection() {
			return this._activeSectionHref;
		}
		get items() {
			return this._itemsData;
		}
		set items(value) {
			this._itemsData = Array.isArray(value) ? value : null;
			this._renderItems();
			this._syncTracking();
		}
		get mode() {
			return this.getAttribute("mode") ?? "auto";
		}
		set mode(value) {
			if (value == null || value === "auto") this.removeAttribute("mode");
			else this.setAttribute("mode", String(value));
		}
		get side() {
			return this.getAttribute("side") === "right" ? "right" : "left";
		}
		set side(value) {
			if (value === "right") this.setAttribute("side", "right");
			else this.removeAttribute("side");
		}
		get label() {
			return this.getAttribute("label") ?? "Navigation";
		}
		set label(value) {
			if (value == null) this.removeAttribute("label");
			else this.setAttribute("label", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		_readDeclarativeItems() {
			const source = Array.from(this.children ?? []);
			if (!source.some((node) => node.tagName === "BISCUIT-SIDEBAR-CATEGORY")) return null;
			const read = (nodes) => nodes.flatMap((node) => {
				if (node.tagName === "A") return [{
					label: node.textContent.trim(),
					href: node.getAttribute("href"),
					current: node.hasAttribute("aria-current"),
					trackSection: node.getAttribute("data-track-section")
				}];
				if (node.tagName === "BISCUIT-SIDEBAR-CATEGORY") return [{
					label: node.getAttribute("label") ?? "Category",
					href: node.getAttribute("href"),
					trackSection: node.getAttribute("data-track-section"),
					children: read(Array.from(node.children))
				}];
				return [];
			});
			return read(source);
		}
		_renderItems() {
			if (!this._items || !this._slot) return;
			const items = this._itemsData ?? this._readDeclarativeItems();
			this._items.hidden = items === null;
			this._slot.hidden = items !== null;
			if (items === null) {
				this._items.replaceChildren();
				return;
			}
			const render = (item) => {
				const children = Array.isArray(item.children) ? item.children : Array.isArray(item.items) ? item.items : null;
				if (children) {
					const category = document.createElement("section");
					category.setAttribute("part", "category");
					const containsCurrent = (entry) => Boolean(entry.current || (entry.children ?? entry.items ?? []).some(containsCurrent));
					if (children.some(containsCurrent)) category.setAttribute("data-current", "");
					const heading = document.createElement(item.href ? "a" : "strong");
					heading.setAttribute("part", item.href ? "category-link" : "category-label");
					heading.textContent = String(item.label ?? "Category");
					if (item.href) heading.setAttribute("href", String(item.href));
					if (item.trackSection) heading.setAttribute("data-track-section", String(item.trackSection));
					if (item.current && item.href) heading.setAttribute("aria-current", "page");
					const nested = document.createElement("div");
					nested.setAttribute("part", "category-items");
					nested.append(...children.map(render));
					category.append(heading, nested);
					return category;
				}
				const link = document.createElement("a");
				link.setAttribute("part", "link");
				link.setAttribute("href", String(item.href ?? "#"));
				if (item.trackSection) link.setAttribute("data-track-section", String(item.trackSection));
				link.textContent = String(item.label ?? item.href ?? "Link");
				if (item.current) link.setAttribute("aria-current", "page");
				return link;
			};
			this._items.replaceChildren(...items.map(render));
		}
		_stopTracking() {
			this._cancelTrackingScroll();
			this._trackingRoot?.removeEventListener?.("scroll", this._onTrackingScroll);
			this.ownerDocument?.defaultView?.removeEventListener?.("resize", this._onTrackingScroll);
			this._trackingRoot = null;
			if (this._trackingFrame != null) this.ownerDocument?.defaultView?.cancelAnimationFrame?.(this._trackingFrame);
			this._trackingFrame = null;
			if (this._activeSectionLink?.getAttribute("aria-current") === "location") this._activeSectionLink.removeAttribute("aria-current");
			this._activeSectionLink?.removeAttribute("data-tracked");
			this._activeSectionLink = null;
			this._activeSectionHref = null;
			this._items?.querySelectorAll?.("[part=\"category\"][data-active]").forEach((node) => node.removeAttribute("data-active"));
		}
		_syncTracking() {
			this._stopTracking();
			if (!this.isConnected || !this.trackScroll) return;
			this._trackingRoot = this._scrollContainer ?? this.ownerDocument?.defaultView;
			this._trackingRoot?.addEventListener?.("scroll", this._onTrackingScroll, { passive: true });
			this.ownerDocument?.defaultView?.addEventListener?.("resize", this._onTrackingScroll);
			this.refreshTracking();
		}
		_scheduleTrackingUpdate() {
			if (this._trackingFrame != null) return;
			const view = this.ownerDocument?.defaultView;
			if (!view?.requestAnimationFrame) {
				this.refreshTracking();
				return;
			}
			this._trackingFrame = view.requestAnimationFrame(() => {
				this._trackingFrame = null;
				this.refreshTracking();
			});
		}
		_cancelTrackingScroll() {
			if (this._trackingScrollFrame != null) this.ownerDocument?.defaultView?.cancelAnimationFrame?.(this._trackingScrollFrame);
			this._trackingScrollFrame = null;
		}
		refreshTracking() {
			if (!this.trackScroll || !this.isConnected) return;
			const doc = this.ownerDocument;
			const location = doc?.defaultView?.location;
			if (!doc || !location) return;
			const sections = (this._items.hidden ? Array.from(this.querySelectorAll?.("a[href]") ?? []) : Array.from(this._items.querySelectorAll("a[href]"))).flatMap((link) => {
				let id;
				const explicit = link.getAttribute("data-track-section");
				if (explicit != null) id = explicit.replace(/^#/, "");
				else {
					let url;
					try {
						url = new URL(link.getAttribute("href"), location.href);
					} catch {
						return [];
					}
					if (url.origin !== location.origin || url.pathname !== location.pathname || !url.hash) return [];
					try {
						id = decodeURIComponent(url.hash.slice(1));
					} catch {
						return [];
					}
				}
				if (!id) return [];
				const target = doc.getElementById(id);
				return target ? [{
					link,
					target,
					href: link.getAttribute("href"),
					id,
					explicit: explicit != null
				}] : [];
			});
			if (!sections.length) return;
			const viewport = this._trackingRoot?.getBoundingClientRect?.();
			const top = viewport?.top ?? 0;
			const height = viewport?.height ?? doc.defaultView.innerHeight;
			const threshold = top + (this.trackScrollOffset ?? Math.min(160, height * .28));
			let active = sections[0];
			let activeTop = -Infinity;
			for (const section of sections) {
				const sectionTop = section.target.getBoundingClientRect().top;
				if (sectionTop <= threshold && sectionTop > activeTop + 1) {
					active = section;
					activeTop = sectionTop;
				}
			}
			const scrollElement = this._trackingRoot?.scrollHeight != null ? this._trackingRoot : doc.scrollingElement ?? doc.documentElement;
			const scrollHeight = scrollElement?.scrollHeight ?? 0;
			const clientHeight = scrollElement?.clientHeight || height;
			const scrollTop = Math.max(scrollElement?.scrollTop ?? 0, this._trackingRoot === doc.defaultView ? doc.defaultView.scrollY ?? 0 : 0);
			if (scrollHeight > clientHeight + 2 && scrollTop + clientHeight >= scrollHeight - 2) active = sections.at(-1);
			if (this._activeSectionLink === active.link) return;
			if (this._activeSectionLink?.getAttribute("aria-current") === "location") this._activeSectionLink.removeAttribute("aria-current");
			this._activeSectionLink?.removeAttribute("data-tracked");
			this._items.querySelectorAll("[part=\"category\"][data-active]").forEach((node) => node.removeAttribute("data-active"));
			this._activeSectionLink = active.link;
			this._activeSectionHref = active.href;
			active.link.setAttribute("data-tracked", "");
			if (!active.explicit && !active.link.hasAttribute("aria-current")) active.link.setAttribute("aria-current", "location");
			for (let parent = active.link.parentElement; parent && parent !== this._items; parent = parent.parentElement) if (parent.getAttribute("part") === "category") parent.setAttribute("data-active", "");
			this._revealTrackedLink(active.link);
			this.dispatchEvent(new CustomEvent("sectionchange", {
				detail: {
					href: active.href,
					id: active.id
				},
				bubbles: true,
				composed: true
			}));
		}
		_revealTrackedLink(link) {
			this._cancelTrackingScroll();
			if (this._isMobile && !this.open) return;
			const box = [this._panel, this].find((node) => node.scrollHeight > node.clientHeight + 2);
			if (!box) return;
			const bounds = box.getBoundingClientRect();
			const linkBounds = link.getBoundingClientRect();
			const inset = 8;
			const position = this.trackScrollPosition;
			let delta = 0;
			if (position === "start") delta = linkBounds.top - bounds.top - inset;
			else if (position === "center") delta = linkBounds.top - bounds.top - (box.clientHeight - linkBounds.height) / 2;
			else if (position === "end") delta = linkBounds.bottom - bounds.bottom + inset;
			else {
				if (linkBounds.top < bounds.top + inset) delta = linkBounds.top - bounds.top - inset;
				else if (link.getAttribute("part") === "category-link") {
					const groupBounds = link.closest("[part=\"category\"]")?.getBoundingClientRect();
					if (groupBounds?.bottom > bounds.bottom - inset) delta = groupBounds.height <= box.clientHeight - 16 ? groupBounds.bottom - bounds.bottom + inset : linkBounds.top - bounds.top - inset;
				}
				if (!delta && linkBounds.bottom > bounds.bottom - inset) delta = linkBounds.bottom - bounds.bottom + inset;
			}
			if (Math.abs(delta) < 1) return;
			const top = Math.max(0, Math.min(box.scrollHeight - box.clientHeight, box.scrollTop + delta));
			const view = this.ownerDocument?.defaultView;
			const reducedMotion = view?.matchMedia?.("(prefers-reduced-motion: reduce)")?.matches;
			if (Math.abs(top - box.scrollTop) < 1) return;
			if (this.trackScrollBehavior === "smooth" && !reducedMotion && view?.requestAnimationFrame) {
				const start = box.scrollTop;
				const distance = top - start;
				const duration = Math.min(210, 120 + Math.abs(distance) * .11);
				const startTime = view.performance?.now?.() ?? 0;
				const step = (time) => {
					const progress = Math.min(1, Math.max(0, (time - startTime) / duration));
					const eased = 1 - (1 - progress) ** 3;
					box.scrollTop = start + distance * eased;
					if (progress < 1) this._trackingScrollFrame = view.requestAnimationFrame(step);
					else this._trackingScrollFrame = null;
				};
				this._trackingScrollFrame = view.requestAnimationFrame(step);
			} else box.scrollTop = top;
		}
		toggle() {
			this._setOpen(!this.open);
		}
		_setOpen(open, restoreFocus = true) {
			if (!this._isMobile || open === this.open) return;
			if (!open && restoreFocus) this._toggle.focus({ preventScroll: true });
			else if (!open && this.contains?.(document.activeElement)) document.activeElement?.blur?.();
			this.open = open;
			if (open) {
				const focusInside = () => {
					if (!this.open || !this._isMobile) return;
					(this._activeSectionLink ?? this._focusables()[0] ?? this._panel).focus({ preventScroll: true });
					if (this._activeSectionLink) this._revealTrackedLink(this._activeSectionLink);
				};
				setTimeout(focusInside, 200);
			}
			this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			}));
		}
		_focusables() {
			const nodes = (this._items && !this._items.hidden ? this._items : this).querySelectorAll?.("a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex=\"-1\"])") ?? [];
			return Array.from(nodes).filter((node) => !node.getClientRects || node.getClientRects().length > 0);
		}
		_handleKeyDown(event) {
			if (!this._isMobile || !this.open) return;
			if (event.key === "Escape") {
				event.preventDefault();
				this._setOpen(false);
			} else if (event.key === "Tab") {
				const nodes = [this._close, ...this._focusables()];
				const active = nodes.find((node) => node === document.activeElement || node === this.shadowRoot.activeElement);
				const first = nodes[0];
				const last = nodes[nodes.length - 1];
				if (!active || event.shiftKey && active === first || !event.shiftKey && active === last) {
					event.preventDefault();
					(event.shiftKey ? last : first).focus();
				}
			}
		}
		_syncMode() {
			const wasMobile = this._isMobile;
			this._isMobile = this.mode === "mobile" || this.mode !== "desktop" && Boolean(this._media?.matches);
			this.toggleAttribute("data-mobile", this._isMobile);
			if (wasMobile && !this._isMobile && this.open) this.open = false;
			this._renderState();
		}
		_renderState() {
			const mobile = this._isMobile;
			const open = this.open;
			const label = this.label;
			this._title.textContent = label;
			this._toggle.hidden = !mobile;
			this._toggle.setAttribute("aria-label", `${open ? "Close" : "Open"} ${label}`);
			this._toggle.setAttribute("aria-expanded", String(mobile && open));
			this._close.hidden = !mobile;
			this._close.setAttribute("aria-label", `Close ${label}`);
			this._backdrop.hidden = !mobile || !open;
			this._panel.inert = mobile && !open;
			this._panel.setAttribute("role", mobile ? "dialog" : "navigation");
			this._panel.setAttribute("aria-label", label);
			if (mobile && open) this._panel.setAttribute("aria-modal", "true");
			else this._panel.removeAttribute("aria-modal");
			if (mobile && !open) this._panel.setAttribute("aria-hidden", "true");
			else this._panel.removeAttribute("aria-hidden");
			document.removeEventListener("keydown", this._onDocumentKeyDown);
			if (this.isConnected && mobile && open) document.addEventListener("keydown", this._onDocumentKeyDown);
		}
	};
	function defineTabLayout() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit TabLayout requires a browser with Custom Elements");
		const existing = registry.get(TABLAYOUT_TAG_NAME);
		if (existing && existing !== BiscuitTabLayout) throw new Error(`<${TABLAYOUT_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(TABLAYOUT_TAG_NAME, BiscuitTabLayout);
		return BiscuitTabLayout;
	}
	function defineSplitView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit SplitView requires a browser with Custom Elements");
		const existing = registry.get(SPLITVIEW_TAG_NAME);
		if (existing && existing !== BiscuitSplitView) throw new Error(`<${SPLITVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(SPLITVIEW_TAG_NAME, BiscuitSplitView);
		return BiscuitSplitView;
	}
	function defineNavBarView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit NavBarView requires a browser with Custom Elements");
		const existing = registry.get(NAVBARVIEW_TAG_NAME);
		if (existing && existing !== BiscuitNavBarView) throw new Error(`<${NAVBARVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(NAVBARVIEW_TAG_NAME, BiscuitNavBarView);
		return BiscuitNavBarView;
	}
	function defineFooterView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit FooterView requires a browser with Custom Elements");
		const existing = registry.get(FOOTERVIEW_TAG_NAME);
		if (existing && existing !== BiscuitFooterView) throw new Error(`<${FOOTERVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(FOOTERVIEW_TAG_NAME, BiscuitFooterView);
		return BiscuitFooterView;
	}
	function defineSidebarView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit SidebarView requires a browser with Custom Elements");
		const existing = registry.get(SIDEBARVIEW_TAG_NAME);
		if (existing && existing !== BiscuitSidebarView) throw new Error(`<${SIDEBARVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(SIDEBARVIEW_TAG_NAME, BiscuitSidebarView);
		return BiscuitSidebarView;
	}
	function createTabLayout(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit TabLayout requires a browser document");
		defineTabLayout();
		const element = createConfiguredElement(TABLAYOUT_TAG_NAME, options);
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "orientation")) element.orientation = options.orientation;
		if (Object.hasOwn(options, "variant")) element.variant = options.variant;
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		for (const item of options.tabs ?? []) {
			const tab = document.createElement("biscuit-tab");
			if (Object.hasOwn(item, "value")) tab.setAttribute("value", String(item.value));
			tab.setAttribute("label", String(item.label ?? item.value ?? "Tab"));
			if (item.disabled) tab.setAttribute("disabled", "");
			if (item.selected) tab.setAttribute("selected", "");
			if (item.content != null) tab.append(item.content);
			element.append(tab);
		}
		if (Object.hasOwn(options, "value")) element.value = options.value;
		element._syncTabs();
		return element;
	}
	function createSplitView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit SplitView requires a browser document");
		defineSplitView();
		const element = createConfiguredElement(SPLITVIEW_TAG_NAME, options);
		for (const key of [
			"orientation",
			"resizable",
			"min",
			"max",
			"leftMin",
			"leftMax",
			"rightMin",
			"rightMax",
			"topMin",
			"topMax",
			"bottomMin",
			"bottomMax",
			"value",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		for (const slot of ["first", "second"]) {
			if (options[slot] == null) continue;
			const content = options[slot];
			const pane = typeof content.setAttribute === "function" ? content : document.createElement("div");
			if (pane !== content) pane.textContent = String(content);
			pane.setAttribute("slot", slot);
			element.append(pane);
		}
		return element;
	}
	function createSidebarView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit SidebarView requires a browser document");
		defineSidebarView();
		const element = createConfiguredElement(SIDEBARVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "label")) element.label = options.label;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "side")) element.side = options.side;
		if (Object.hasOwn(options, "mode")) element.mode = options.mode;
		if (Object.hasOwn(options, "open")) element.open = options.open;
		if (Object.hasOwn(options, "sticky")) element.sticky = options.sticky;
		if (Object.hasOwn(options, "scrollContainer")) element.scrollContainer = options.scrollContainer;
		if (options.items?.some((item) => Array.isArray(item.children) || Array.isArray(item.items))) element.items = options.items;
		else for (const item of options.items ?? []) {
			const link = document.createElement("a");
			link.setAttribute("href", String(item.href));
			if (item.trackSection) link.setAttribute("data-track-section", String(item.trackSection));
			link.textContent = String(item.label);
			if (item.current) link.setAttribute("aria-current", "page");
			element.append(link);
		}
		if (options.content != null) element.append(options.content);
		if (Object.hasOwn(options, "trackScroll")) element.trackScroll = options.trackScroll;
		if (Object.hasOwn(options, "trackScrollBehavior")) element.trackScrollBehavior = options.trackScrollBehavior;
		if (Object.hasOwn(options, "trackScrollPosition")) element.trackScrollPosition = options.trackScrollPosition;
		if (Object.hasOwn(options, "trackScrollOffset")) element.trackScrollOffset = options.trackScrollOffset;
		return element;
	}
	function createNavBarView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit NavBarView requires a browser document");
		defineNavBarView();
		const element = createConfiguredElement(NAVBARVIEW_TAG_NAME, options);
		for (const key of [
			"brand",
			"brandHref",
			"mode",
			"open",
			"sticky",
			"fullWidth",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		for (const item of options.items ?? []) {
			const link = document.createElement("a");
			link.setAttribute("href", String(item.href));
			link.textContent = String(item.label);
			if (item.current) link.setAttribute("aria-current", "page");
			element.append(link);
		}
		if (options.actions != null) {
			const actions = Array.isArray(options.actions) ? options.actions : [options.actions];
			for (const action of actions) {
				if (typeof action?.setAttribute !== "function") throw new TypeError("NavBarView actions must be elements");
				action.setAttribute("slot", "actions");
				element.append(action);
			}
		}
		element._syncActions();
		return element;
	}
	function createFooterView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit FooterView requires a browser document");
		defineFooterView();
		const element = createConfiguredElement(FOOTERVIEW_TAG_NAME, options);
		for (const key of [
			"brand",
			"brandHref",
			"description",
			"copyright",
			"fullWidth",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		if (options.groups != null) element.groups = options.groups;
		for (const [name, content] of [
			["brand", options.brandContent],
			["description", options.descriptionContent],
			["actions", options.actions],
			["bottom", options.bottom]
		]) {
			if (content == null) continue;
			for (const node of Array.isArray(content) ? content : [content]) {
				if (typeof node?.setAttribute !== "function") throw new TypeError(`FooterView ${name} content must be elements`);
				node.setAttribute("slot", name);
				element.append(node);
			}
		}
		element._syncContent();
		return element;
	}
	function defineLayout() {
		defineSplitView();
		defineTabLayout();
		defineNavBarView();
		defineFooterView();
		defineSidebarView();
	}
	// src/groups/media.js
	var media_exports = /* @__PURE__ */ __exportAll({
		BiscuitCarouselView: () => BiscuitCarouselView,
		BiscuitFileBrowserView: () => BiscuitFileBrowserView,
		BiscuitIconGridView: () => BiscuitIconGridView,
		BiscuitImageView: () => BiscuitImageView,
		createCarouselView: () => createCarouselView,
		createFileBrowserView: () => createFileBrowserView,
		createIconGridView: () => createIconGridView,
		createImageView: () => createImageView,
		defineCarouselView: () => defineCarouselView,
		defineFileBrowserView: () => defineFileBrowserView,
		defineIconGridView: () => defineIconGridView,
		defineImageView: () => defineImageView,
		defineMedia: () => defineMedia
	});
	var IMAGEVIEW_TAG_NAME = "biscuit-imageview";
	var CAROUSELVIEW_TAG_NAME = "biscuit-carouselview";
	var ICONGRIDVIEW_TAG_NAME = "biscuit-icongridview";
	var FILEBROWSERVIEW_TAG_NAME = "biscuit-filebrowserview";
	var carouselViewId = 0;
	var iconGridViewStyles = `
  :host {
    box-sizing: border-box; display: block; min-width: 0; padding: var(--biscuit-icongridview-padding, calc(1.5rem * var(--biscuit-space-scale, 1)));
    border-radius: var(--biscuit-icongridview-radius, 1rem);
    background: var(--biscuit-icongridview-background, light-dark(#f3f4f8, #171920));
    color: var(--biscuit-icongridview-color, var(--biscuit-theme-color, light-dark(#252936, #f4f6fa)));
    color-scheme: inherit; font: 400 0.875rem/1.4 system-ui, sans-serif;
  }
  ${themeStyles}
  [part="heading"] { display: block; margin: 0 0 1.25rem; font-size: 1.5rem; font-weight: 750; }
  [part="heading"][hidden], [part="label"][hidden] { display: none; }
  [part="grid"] { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(100%, var(--biscuit-icongridview-tile-min-width, 7rem)), 1fr)); gap: var(--biscuit-icongridview-gap, calc(1rem * var(--biscuit-space-scale, 1))); }
  [part="tile"] { box-sizing: border-box; display: grid; justify-items: center; align-content: center; gap: 0.75rem; min-height: 7rem; padding: 1rem 0.5rem;
    border: 1px solid var(--biscuit-icongridview-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #535b6a)));
    border-radius: var(--biscuit-icongridview-tile-radius, 0.9rem);
    background: var(--biscuit-icongridview-tile-background, var(--biscuit-theme-surface, light-dark(#fff, #292c37)));
  }
  [part="icon"] { display: inline-flex; align-items: center; justify-content: center; width: 1em; height: 1em;
    font-size: var(--biscuit-icongridview-icon-size, 2.5rem); line-height: 1; }
  [part="icon"] > svg, [part="icon"] > img { display: block; width: 100%; height: 100%; object-fit: contain; }
  [part="label"] { max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--biscuit-icongridview-label-color, light-dark(#687080, #b9c0cc)); font-size: 0.8rem; }
  :host([theme="deep-dark"]) { --biscuit-icongridview-background: #000; --biscuit-icongridview-color: #fff; --biscuit-icongridview-tile-background: #000; --biscuit-icongridview-border-color: #fff; --biscuit-icongridview-label-color: #fff; }
`;
	var fileBrowserViewStyles = `
  :host { box-sizing: border-box; display: block; min-width: 0; width: 100%; overflow: hidden; color-scheme: inherit;
    border: var(--biscuit-filebrowserview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-filebrowserview-radius, 0.65rem);
    background: var(--biscuit-filebrowserview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-filebrowserview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-filebrowserview-font, 400 0.875rem/1.4 system-ui, sans-serif);
  }
  ${themeStyles}
  [hidden] { display: none !important; }
  [part="toolbar"] { display: flex; align-items: center; gap: 0.75rem; min-height: 2.75rem; padding: 0.55rem 0.75rem;
    border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))); }
  [part="title"] { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 750; }
  [part="search-wrap"] { display: flex; align-items: center; gap: 0.35rem; width: min(12rem, 55%); padding: 0.3rem 0.5rem;
    border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))); border-radius: 0.4rem; }
  [part="search-icon"] { opacity: 0.65; }
  [part="search"] { min-width: 0; width: 100%; border: 0; outline: 0; background: transparent; color: inherit; font: inherit; }
  [part="search"]::placeholder { color: inherit; opacity: 0.55; }
  [part="tree"] { max-height: var(--biscuit-filebrowserview-height, 23rem); overflow: auto; padding: 0.45rem; }
  [part="row"] { box-sizing: border-box; display: flex; align-items: center; gap: 0.55rem; width: 100%; min-height: 2rem;
    padding: 0.25rem 0.65rem 0.25rem calc(0.65rem + var(--depth) * 1.2rem); border: 0; border-radius: 0.4rem;
    background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
  [part="row"]:hover { background: var(--biscuit-filebrowserview-hover-background, color-mix(in srgb, currentColor 9%, transparent)); }
  [part="row"][aria-selected="true"] { background: var(--biscuit-filebrowserview-selected-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f)));
    color: var(--biscuit-filebrowserview-selected-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff))); }
  [part="row"]:focus-visible, [part="search"]:focus-visible { outline: 2px solid var(--biscuit-filebrowserview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff)))); outline-offset: -2px; }
  [part="chevron"], [part="spacer"] { display: inline-flex; align-items: center; justify-content: center; width: 0.9rem; height: 0.9rem; flex: none; font-size: 0.8rem; }
  [part="icon"] { font-size: 1.15rem; line-height: 1; }
  [part="name"] { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  [part="empty"] { padding: 1rem; opacity: 0.7; }
  :host([theme="deep-dark"]) { --biscuit-filebrowserview-hover-background: #222; --biscuit-filebrowserview-selected-background: #fff; --biscuit-filebrowserview-selected-color: #000; }
`;
	var imageViewStyles = `
  :host {
    box-sizing: border-box;
    display: block;
    min-width: 0;
    overflow: hidden;
    color-scheme: inherit;
    border: var(--biscuit-imageview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-imageview-radius, 0.75rem);
    background: var(--biscuit-imageview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-imageview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-imageview-font, 400 0.875rem/1.45 system-ui, sans-serif);
  }
  ${themeStyles}
  [hidden] { display: none !important; }
  [part="figure"] { margin: 0; }
  [part="frame"] {
    display: grid;
    place-items: center;
    position: relative;
    width: 100%;
    aspect-ratio: var(--biscuit-imageview-aspect-ratio, 16 / 9);
    overflow: hidden;
    background: var(--biscuit-imageview-frame-background, light-dark(#f0f1f5, #20232c));
  }
  [part="image"] {
    display: block;
    width: 100%;
    height: 100%;
    min-height: 0;
    object-fit: var(--biscuit-imageview-fit, cover);
    object-position: var(--biscuit-imageview-position, center);
    opacity: 0;
  }
  :host([data-state="loaded"]) [part="image"] { opacity: 1; }
  [part="loading"], [part="fallback"] {
    position: absolute;
    inset: 0;
    display: grid;
    place-items: center;
    padding: 1rem;
    text-align: center;
  }
  [part="fallback"] { color: var(--biscuit-imageview-muted-color, light-dark(#646677, #b1b5c5)); }
  .image-spinner { width: 1.25rem; height: 1.25rem; border: 2px solid currentColor; border-right-color: transparent; border-radius: 50%; animation: biscuit-image-spin .8s linear infinite; }
  @keyframes biscuit-image-spin { to { transform: rotate(360deg); } }
  @media (prefers-reduced-motion: reduce) { .image-spinner { animation: none; } }
  [part="caption"] { padding: var(--biscuit-imageview-caption-padding, 0.75rem 0.9rem); }
`;
	var carouselViewStyles = `
  :host {
    box-sizing: border-box;
    display: block;
    min-width: 0;
    color-scheme: inherit;
    border: var(--biscuit-carouselview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a))));
    border-radius: var(--biscuit-carouselview-radius, 0.75rem);
    background: var(--biscuit-carouselview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-carouselview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-carouselview-font, 400 0.9375rem/1.45 system-ui, sans-serif);
  }
  ${themeStyles}
  [hidden] { display: none !important; }
  [part="viewport"] {
    min-width: 0;
    overflow: hidden;
    touch-action: pan-y;
  }
  [part="viewport"]:focus-visible,
  [part="previous"]:focus-visible,
  [part="next"]:focus-visible,
  [part="dot"]:focus-visible {
    outline: 2px solid var(--biscuit-carouselview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: -2px;
  }
  [part="slide"] {
    min-width: 0;
    min-height: var(--biscuit-carouselview-slide-min-height, 12rem);
  }
  ::slotted(biscuit-slide) { display: block; }
  [part="controls"] {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 0.75rem;
    padding: var(--biscuit-carouselview-controls-padding, 0.65rem 0.8rem);
  }
  [part="previous"], [part="next"] {
    flex: none;
    display: grid;
    place-items: center;
    width: 2.25rem;
    height: 2.25rem;
    border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    border-radius: 50%;
    background: var(--biscuit-carouselview-control-background, transparent);
    color: inherit;
    font: inherit;
    font-size: 1.4rem;
    line-height: 1;
    cursor: pointer;
  }
  [part="previous"]:hover:not(:disabled), [part="next"]:hover:not(:disabled) {
    background: var(--biscuit-carouselview-hover-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f)));
    color: var(--biscuit-carouselview-hover-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff)));
  }
  [part="previous"]:disabled, [part="next"]:disabled { opacity: 0.4; cursor: not-allowed; }
  [part="dots"] { display: flex; flex-wrap: wrap; justify-content: center; gap: 0.45rem; }
  [part="dot"] {
    width: 0.7rem;
    height: 0.7rem;
    padding: 0;
    border: 0;
    border-radius: 50%;
    background: var(--biscuit-carouselview-dot-color, light-dark(#b4b7c3, #6d7280));
    cursor: pointer;
  }
  [part="dot"][aria-current="true"] {
    background: var(--biscuit-carouselview-active-dot-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    transform: scale(1.2);
  }
  [part="status"] {
    position: absolute;
    width: 1px;
    height: 1px;
    overflow: hidden;
    clip-path: inset(50%);
    white-space: nowrap;
  }
`;
	function normalizeIconGridItems(items) {
		if (!Array.isArray(items)) throw new TypeError("IconGridView icons must be an array");
		return items.map((entry) => {
			if (typeof entry === "string") {
				getIconSvg(entry);
				return {
					name: entry,
					label: entry,
					icon: entry
				};
			}
			if (!entry || typeof entry !== "object") throw new TypeError("IconGridView icons must be names or icon objects");
			const name = String(entry.name ?? entry.label ?? "").trim();
			if (!name) throw new TypeError("Custom icons need a name or label");
			const label = String(entry.label ?? name);
			const src = entry.src == null ? null : String(entry.src).trim();
			const element = entry.element ?? null;
			if (src && element) throw new TypeError("Custom icons need either src or element, not both");
			if (element && (element.nodeType !== 1 || typeof element.cloneNode !== "function")) throw new TypeError("Custom icon element must be a DOM element");
			const icon = src || element ? null : String(entry.icon ?? name);
			if (icon) getIconSvg(icon);
			if (entry.src != null && !src) throw new TypeError("Custom icon src must not be empty");
			return {
				name,
				label,
				icon,
				src,
				element
			};
		});
	}
	var BiscuitIconGridView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["heading", "hide-labels"];
		}
		constructor() {
			super();
			this._icons = null;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = iconGridViewStyles;
			this._heading = document.createElement("strong");
			this._heading.setAttribute("part", "heading");
			this._grid = document.createElement("div");
			this._grid.setAttribute("part", "grid");
			root.append(style, this._heading, this._grid);
			this._render();
		}
		connectedCallback() {
			this._render();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver(() => {
					if (this._icons === null) this._render();
				});
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					attributes: true
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		attributeChangedCallback() {
			this._render();
		}
		get icons() {
			return this._icons?.slice() ?? this._readMarkup();
		}
		set icons(value) {
			if (value !== null) normalizeIconGridItems(value);
			this._icons = value?.slice() ?? null;
			this._render();
		}
		get heading() {
			return this.getAttribute("heading") ?? "Biscuit icons";
		}
		set heading(value) {
			if (value == null) this.removeAttribute("heading");
			else this.setAttribute("heading", String(value));
		}
		get showLabels() {
			return !this.hasAttribute("hide-labels");
		}
		set showLabels(value) {
			this.toggleAttribute("hide-labels", !Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		_readMarkup() {
			const nodes = Array.from(this.children ?? []).filter((node) => (node.localName ?? node.tagName?.toLowerCase()) === "biscuit-icon");
			if (!nodes.length) return iconNames.slice();
			return nodes.map((node) => ({
				name: node.getAttribute("name") ?? node.getAttribute("label"),
				label: node.getAttribute("label") ?? void 0,
				icon: node.getAttribute("icon") ?? void 0,
				src: node.getAttribute("src") ?? void 0,
				element: node.firstElementChild ?? void 0
			}));
		}
		_render() {
			if (!this._grid) return;
			this._heading.textContent = this.heading;
			this._heading.hidden = !this.heading;
			const tiles = normalizeIconGridItems(this.icons).map(({ name, label: caption, icon: iconName, src, element }) => {
				const tile = document.createElement("div");
				tile.setAttribute("part", "tile");
				let icon;
				if (iconName) icon = createIcon(iconName, this.showLabels ? {} : { label: caption });
				else {
					icon = document.createElement("span");
					if (src) {
						const image = document.createElement("img");
						image.src = src;
						image.alt = this.showLabels ? "" : caption;
						icon.append(image);
					} else {
						icon.append(element.cloneNode(true));
						if (this.showLabels) icon.setAttribute("aria-hidden", "true");
						else {
							icon.setAttribute("role", "img");
							icon.setAttribute("aria-label", caption);
						}
					}
				}
				icon.setAttribute("part", "icon");
				const label = document.createElement("span");
				label.setAttribute("part", "label");
				label.hidden = !this.showLabels;
				label.textContent = caption;
				tile.append(icon, label);
				return tile;
			});
			this._grid.replaceChildren(...tiles);
		}
	};
	function normalizeFileBrowserItems(items, parentPath = "") {
		if (!Array.isArray(items)) throw new TypeError("FileBrowserView items must be an array");
		return items.map((entry) => {
			const item = typeof entry === "string" ? { name: entry } : entry;
			if (!item || typeof item !== "object" || !String(item.name ?? "").trim()) throw new TypeError("FileBrowserView items need a name");
			const name = String(item.name);
			const path = String(item.path ?? (parentPath ? `${parentPath}/${name}` : name));
			const folder = item.type === "folder" || item.type === "directory" || Array.isArray(item.children);
			return {
				name,
				path,
				folder,
				expanded: Boolean(item.expanded),
				icon: item.icon && Object.hasOwn(iconSvgs, item.icon) ? String(item.icon) : null,
				children: folder ? normalizeFileBrowserItems(item.children ?? [], path) : []
			};
		});
	}
	function fileBrowserIcon(item) {
		if (item.icon) return item.icon;
		if (item.folder) return "folder";
		const ext = item.name.split(".").at(-1).toLowerCase();
		if (["html", "htm"].includes(ext)) return "html-file";
		if ([
			"js",
			"mjs",
			"cjs",
			"jsx"
		].includes(ext)) return "js-file";
		if (ext === "css") return "css-file";
		if (ext === "svg") return "svg-file";
		if ([
			"png",
			"jpg",
			"jpeg",
			"gif",
			"webp",
			"avif",
			"bmp",
			"ico"
		].includes(ext)) return "image-file";
		if ([
			"ts",
			"tsx",
			"java",
			"py",
			"rb",
			"go",
			"rs",
			"php",
			"sh",
			"json",
			"xml",
			"yaml",
			"yml",
			"md"
		].includes(ext)) return "code-file";
		return "file";
	}
	var BiscuitFileBrowserView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"label",
				"searchable",
				"value"
			];
		}
		constructor() {
			super();
			this._items = null;
			this._expanded = /* @__PURE__ */ new Set();
			this._focusedPath = null;
			this._visible = [];
			this._rows = /* @__PURE__ */ new Map();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = fileBrowserViewStyles;
			this._toolbar = document.createElement("div");
			this._toolbar.setAttribute("part", "toolbar");
			this._title = document.createElement("strong");
			this._title.setAttribute("part", "title");
			this._searchWrap = document.createElement("label");
			this._searchWrap.setAttribute("part", "search-wrap");
			const searchIcon = createIcon("search");
			searchIcon.setAttribute("part", "search-icon");
			this._search = document.createElement("input");
			this._search.type = "search";
			this._search.placeholder = "Find a file";
			this._search.setAttribute("part", "search");
			this._search.setAttribute("aria-label", "Find a file");
			this._search.addEventListener("input", () => this._render());
			this._searchWrap.append(searchIcon, this._search);
			this._toolbar.append(this._title, this._searchWrap);
			this._tree = document.createElement("div");
			this._tree.setAttribute("part", "tree");
			this._tree.setAttribute("role", "tree");
			root.append(style, this._toolbar, this._tree);
			this._render();
		}
		connectedCallback() {
			if (!this._items && !this._markupInitialized) {
				this._collectInitiallyExpanded(this._readMarkup());
				this._markupInitialized = true;
			}
			this._render();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver(() => {
					if (!this._items) this._render();
				});
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					attributes: true
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		attributeChangedCallback() {
			if (this._tree) this._render();
		}
		get items() {
			return this._items ?? this._readMarkup();
		}
		set items(value) {
			this._items = normalizeFileBrowserItems(value);
			this._expanded.clear();
			this._collectInitiallyExpanded(this._items);
			this._render();
		}
		get label() {
			return this.getAttribute("label") ?? "Files";
		}
		set label(value) {
			if (value == null) this.removeAttribute("label");
			else this.setAttribute("label", String(value));
		}
		get searchable() {
			return this.hasAttribute("searchable");
		}
		set searchable(value) {
			this.toggleAttribute("searchable", Boolean(value));
		}
		get value() {
			return this.getAttribute("value") ?? "";
		}
		set value(value) {
			if (value == null || value === "") this.removeAttribute("value");
			else this.setAttribute("value", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		openAll() {
			this._collectAllFolders(this.items);
			this._render();
		}
		collapseAll() {
			this._expanded.clear();
			this._render();
		}
		refresh() {
			this._render();
		}
		_readMarkup() {
			const parse = (parent) => Array.from(parent.children ?? []).flatMap((node) => {
				const tag = node.localName ?? node.tagName?.toLowerCase();
				if (tag !== "biscuit-folder" && tag !== "biscuit-file") return [];
				return [{
					name: node.getAttribute("name") ?? node.textContent?.trim(),
					path: node.getAttribute("path") ?? void 0,
					type: tag === "biscuit-folder" ? "folder" : "file",
					expanded: node.hasAttribute("open"),
					icon: node.getAttribute("icon") ?? void 0,
					children: tag === "biscuit-folder" ? parse(node) : void 0
				}];
			});
			return normalizeFileBrowserItems(parse(this));
		}
		_collectInitiallyExpanded(items) {
			for (const item of items) {
				if (item.folder && item.expanded) this._expanded.add(item.path);
				this._collectInitiallyExpanded(item.children);
			}
		}
		_collectAllFolders(items) {
			for (const item of items) {
				if (item.folder) this._expanded.add(item.path);
				this._collectAllFolders(item.children);
			}
		}
		_render() {
			if (!this._tree) return;
			const items = this.items;
			this._title.textContent = this.label;
			this._tree.setAttribute("aria-label", this.label);
			this._searchWrap.hidden = !this.searchable;
			const query = this.searchable ? this._search.value.trim().toLowerCase() : "";
			const matches = (item) => !query || item.name.toLowerCase().includes(query) || item.children.some(matches);
			const visible = [];
			const walk = (entries, depth, parentPath = "") => {
				for (const item of entries) {
					if (!matches(item)) continue;
					visible.push({
						item,
						depth,
						parentPath
					});
					if (item.folder && (query || this._expanded.has(item.path))) walk(item.children, depth + 1, item.path);
				}
			};
			walk(items, 0);
			this._visible = visible;
			if (!visible.some(({ item }) => item.path === this._focusedPath)) this._focusedPath = visible[0]?.item.path ?? null;
			this._rows = /* @__PURE__ */ new Map();
			const nodes = visible.map(({ item, depth }) => {
				const row = document.createElement("button");
				row.type = "button";
				row.setAttribute("part", "row");
				row.setAttribute("role", "treeitem");
				row.setAttribute("aria-level", String(depth + 1));
				row.setAttribute("aria-selected", String(!item.folder && this.value === item.path));
				row.setAttribute("aria-label", item.path);
				if (item.folder) row.setAttribute("aria-expanded", String(Boolean(query || this._expanded.has(item.path))));
				row.style.setProperty("--depth", String(depth));
				row.tabIndex = item.path === this._focusedPath ? 0 : -1;
				const chevron = document.createElement("span");
				chevron.setAttribute("part", item.folder ? "chevron" : "spacer");
				if (item.folder) chevron.append(createIcon(query || this._expanded.has(item.path) ? "chevron-down" : "chevron-right"));
				const icon = createIcon(fileBrowserIcon(item));
				icon.setAttribute("part", "icon");
				const name = document.createElement("span");
				name.setAttribute("part", "name");
				name.textContent = item.name;
				row.append(chevron, icon, name);
				row.addEventListener("focus", () => {
					this._focusedPath = item.path;
				});
				row.addEventListener("click", () => this._activate(item));
				row.addEventListener("keydown", (event) => this._onRowKeyDown(event, item));
				this._rows.set(item.path, row);
				return row;
			});
			if (!nodes.length) {
				const empty = document.createElement("div");
				empty.setAttribute("part", "empty");
				empty.textContent = query ? "No matching files" : "No files";
				nodes.push(empty);
			}
			this._tree.replaceChildren(...nodes);
		}
		_activate(item) {
			this._focusedPath = item.path;
			if (item.folder) {
				if (this.searchable && this._search.value.trim()) return;
				const expanded = !this._expanded.has(item.path);
				if (expanded) this._expanded.add(item.path);
				else this._expanded.delete(item.path);
				this._render();
				this._focusPath(item.path);
				const event = new Event("toggle", {
					bubbles: true,
					composed: true
				});
				event.detail = {
					item,
					path: item.path,
					expanded
				};
				this.dispatchEvent(event);
				return;
			}
			this.value = item.path;
			this._focusPath(item.path);
			for (const type of ["select", "change"]) {
				const event = new Event(type, {
					bubbles: true,
					composed: true
				});
				event.detail = {
					item,
					path: item.path
				};
				this.dispatchEvent(event);
			}
		}
		_focusPath(path) {
			this._focusedPath = path;
			for (const [key, row] of this._rows) row.tabIndex = key === path ? 0 : -1;
			this._rows.get(path)?.focus();
		}
		_onRowKeyDown(event, item) {
			const index = this._visible.findIndex(({ item: entry }) => entry.path === item.path);
			let target = null;
			if (event.key === "ArrowDown") target = this._visible[index + 1]?.item.path;
			else if (event.key === "ArrowUp") target = this._visible[index - 1]?.item.path;
			else if (event.key === "Home") target = this._visible[0]?.item.path;
			else if (event.key === "End") target = this._visible.at(-1)?.item.path;
			else if (event.key === "ArrowRight" && item.folder) {
				if (!this._expanded.has(item.path)) this._activate(item);
				else if (this._visible[index + 1]?.depth > this._visible[index]?.depth) target = this._visible[index + 1].item.path;
			} else if (event.key === "ArrowLeft") {
				if (item.folder && this._expanded.has(item.path)) this._activate(item);
				else target = this._visible[index]?.parentPath;
			} else if (event.key === "Enter" || event.key === " ") {
				event.preventDefault();
				this._activate(item);
				return;
			} else return;
			event.preventDefault();
			if (target) this._focusPath(target);
		}
	};
	var BiscuitImageView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"src",
				"alt",
				"fit",
				"aspect-ratio",
				"caption",
				"fallback",
				"loading",
				"srcset",
				"sizes"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = imageViewStyles;
			const figure = document.createElement("figure");
			figure.setAttribute("part", "figure");
			this._frame = document.createElement("div");
			this._frame.setAttribute("part", "frame");
			this._image = document.createElement("img");
			this._image.setAttribute("part", "image");
			this._image.setAttribute("alt", "");
			this._image.setAttribute("loading", "lazy");
			this._image.addEventListener("load", () => {
				if (!this.src) return;
				this._setState("loaded");
				this.dispatchEvent(new Event("load"));
			});
			this._image.addEventListener("error", () => {
				if (!this.src) return;
				this._setState("error");
				this.dispatchEvent(new Event("error"));
			});
			this._loading = document.createElement("div");
			this._loading.setAttribute("part", "loading");
			const indicator = document.createElement("span");
			indicator.className = "image-spinner";
			indicator.setAttribute("role", "status");
			indicator.setAttribute("aria-label", "Loading image");
			this._loading.append(indicator);
			this._fallback = document.createElement("div");
			this._fallback.setAttribute("part", "fallback");
			this._caption = document.createElement("figcaption");
			this._caption.setAttribute("part", "caption");
			this._caption.hidden = true;
			this._frame.append(this._image, this._loading, this._fallback);
			figure.append(this._frame, this._caption);
			root.append(style, figure);
		}
		connectedCallback() {
			if (!this.hasAttribute("data-state")) this._syncSource();
			else if (this.src && this._image.complete && this._image.naturalWidth > 0) this._setState("loaded");
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue === newValue) return;
			if (name === "src") this._syncSource();
			if (name === "alt") this._image.setAttribute("alt", newValue ?? "");
			if (name === "fit") {
				if (newValue && [
					"cover",
					"contain",
					"fill",
					"none",
					"scale-down"
				].includes(newValue)) this._image.style.setProperty("object-fit", newValue);
				else this._image.style.removeProperty("object-fit");
			}
			if (name === "aspect-ratio") {
				if (newValue) this._frame.style.setProperty("aspect-ratio", newValue);
				else this._frame.style.removeProperty("aspect-ratio");
			}
			if (name === "caption") {
				this._caption.textContent = newValue ?? "";
				this._caption.hidden = !newValue;
			}
			if (name === "fallback") this._syncFallback();
			if (name === "loading") this._image.setAttribute("loading", newValue || "lazy");
			if (name === "srcset" || name === "sizes") {
				if (newValue) this._image.setAttribute(name, newValue);
				else this._image.removeAttribute(name);
			}
		}
		_syncSource() {
			const src = this.src;
			if (src) {
				this._setState("loading");
				this._image.setAttribute("src", src);
				if (this._image.complete && this._image.naturalWidth > 0) this._setState("loaded");
			} else {
				this._image.removeAttribute("src");
				this._setState("empty");
			}
		}
		_syncFallback() {
			this._fallback.textContent = this.fallback || (this.state === "error" ? "Image unavailable" : "No image selected");
		}
		_setState(state) {
			this.setAttribute("data-state", state);
			this._loading.hidden = state !== "loading";
			this._fallback.hidden = state !== "empty" && state !== "error";
			this._syncFallback();
		}
		get state() {
			return this.getAttribute("data-state") ?? "empty";
		}
		get src() {
			return this.getAttribute("src") ?? "";
		}
		set src(value) {
			if (value == null || value === "") this.removeAttribute("src");
			else this.setAttribute("src", String(value));
		}
		get alt() {
			return this.getAttribute("alt") ?? "";
		}
		set alt(value) {
			this.setAttribute("alt", String(value ?? ""));
		}
		get fit() {
			return this.getAttribute("fit") ?? "cover";
		}
		set fit(value) {
			this.setAttribute("fit", String(value ?? "cover"));
		}
		get aspectRatio() {
			return this.getAttribute("aspect-ratio") ?? "16 / 9";
		}
		set aspectRatio(value) {
			if (value == null || value === "") this.removeAttribute("aspect-ratio");
			else this.setAttribute("aspect-ratio", String(value));
		}
		get caption() {
			return this.getAttribute("caption") ?? "";
		}
		set caption(value) {
			if (value == null || value === "") this.removeAttribute("caption");
			else this.setAttribute("caption", String(value));
		}
		get fallback() {
			return this.getAttribute("fallback") ?? "";
		}
		set fallback(value) {
			if (value == null || value === "") this.removeAttribute("fallback");
			else this.setAttribute("fallback", String(value));
		}
		get loading() {
			return this.getAttribute("loading") ?? "lazy";
		}
		set loading(value) {
			this.setAttribute("loading", String(value ?? "lazy"));
		}
		get srcset() {
			return this.getAttribute("srcset") ?? "";
		}
		set srcset(value) {
			if (value == null || value === "") this.removeAttribute("srcset");
			else this.setAttribute("srcset", String(value));
		}
		get sizes() {
			return this.getAttribute("sizes") ?? "";
		}
		set sizes(value) {
			if (value == null || value === "") this.removeAttribute("sizes");
			else this.setAttribute("sizes", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
	};
	var BiscuitCarouselView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["value", "loop"];
		}
		constructor() {
			super();
			this._id = ++carouselViewId;
			this._slides = [];
			this._panels = [];
			this._dots = [];
			this._value = "";
			this._selectedIndex = -1;
			this._pointerStart = null;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = carouselViewStyles;
			this._viewport = document.createElement("div");
			this._viewport.setAttribute("part", "viewport");
			this._viewport.tabIndex = 0;
			this._viewport.addEventListener("keydown", (event) => this._onKeyDown(event));
			this._viewport.addEventListener("pointerdown", (event) => this._onPointerDown(event));
			this._viewport.addEventListener("pointerup", (event) => this._onPointerUp(event));
			this._viewport.addEventListener("pointercancel", () => {
				this._pointerStart = null;
			});
			this._controls = document.createElement("div");
			this._controls.setAttribute("part", "controls");
			this._previous = document.createElement("button");
			this._previous.type = "button";
			this._previous.setAttribute("part", "previous");
			this._previous.setAttribute("aria-label", "Previous slide");
			this._previous.textContent = "‹";
			this._previous.addEventListener("click", () => this.previous());
			this._dotList = document.createElement("div");
			this._dotList.setAttribute("part", "dots");
			this._next = document.createElement("button");
			this._next.type = "button";
			this._next.setAttribute("part", "next");
			this._next.setAttribute("aria-label", "Next slide");
			this._next.textContent = "›";
			this._next.addEventListener("click", () => this.next());
			this._controls.append(this._previous, this._dotList, this._next);
			this._status = document.createElement("div");
			this._status.setAttribute("part", "status");
			this._status.setAttribute("aria-live", "polite");
			root.append(style, this._viewport, this._controls, this._status);
		}
		connectedCallback() {
			if (!this.hasAttribute("role")) this.setAttribute("role", "region");
			if (!this.hasAttribute("aria-roledescription")) this.setAttribute("aria-roledescription", "carousel");
			if (!this.hasAttribute("aria-label")) this.setAttribute("aria-label", "Carousel");
			this._syncSlides();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver((mutations) => {
					if (mutations.some((mutation) => mutation.type === "childList" && mutation.target === this || mutation.type === "attributes" && mutation.target.parentElement === this)) this._syncSlides();
				});
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					attributes: true,
					attributeFilter: [
						"value",
						"label",
						"selected"
					]
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._observer = null;
			this._pointerStart = null;
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue !== newValue) this._applySelection();
		}
		get value() {
			return this._value;
		}
		set value(value) {
			this.setAttribute("value", String(value ?? ""));
		}
		get selectedIndex() {
			return this._selectedIndex;
		}
		get loop() {
			return this.hasAttribute("loop");
		}
		set loop(value) {
			this.toggleAttribute("loop", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		next() {
			if (this._slides.length < 2) return;
			const next = this._selectedIndex + 1;
			if (next < this._slides.length) this._selectIndex(next);
			else if (this.loop) this._selectIndex(0);
		}
		previous() {
			if (this._slides.length < 2) return;
			const previous = this._selectedIndex - 1;
			if (previous >= 0) this._selectIndex(previous);
			else if (this.loop) this._selectIndex(this._slides.length - 1);
		}
		_syncSlides() {
			this._slides = Array.from(this.children).filter((child) => child.tagName === "BISCUIT-SLIDE").map((element, index) => ({
				element,
				value: element.getAttribute("value") ?? `slide-${index + 1}`,
				label: element.getAttribute("label") ?? `Slide ${index + 1}`,
				selected: element.hasAttribute("selected")
			}));
			this._panels = this._slides.map((slide, index) => {
				slide.element.setAttribute("slot", `biscuit-slide-${this._id}-${index}`);
				const panel = document.createElement("div");
				panel.setAttribute("part", "slide");
				panel.setAttribute("role", "group");
				panel.setAttribute("aria-roledescription", "slide");
				panel.setAttribute("aria-label", `${index + 1} of ${this._slides.length}: ${slide.label}`);
				panel.id = `biscuit-carousel-slide-${this._id}-${index}`;
				const slot = document.createElement("slot");
				slot.name = `biscuit-slide-${this._id}-${index}`;
				panel.append(slot);
				return panel;
			});
			this._dots = this._slides.map((slide, index) => {
				const dot = document.createElement("button");
				dot.type = "button";
				dot.setAttribute("part", "dot");
				dot.setAttribute("aria-label", `Go to ${slide.label}`);
				dot.setAttribute("aria-controls", `biscuit-carousel-slide-${this._id}-${index}`);
				dot.addEventListener("click", () => this._selectIndex(index));
				return dot;
			});
			this._viewport.replaceChildren(...this._panels);
			this._dotList.replaceChildren(...this._dots);
			this._applySelection();
		}
		_applySelection() {
			const requested = this.getAttribute("value");
			let index = requested === null ? this._slides.findIndex((slide) => slide.selected) : this._slides.findIndex((slide) => slide.value === requested);
			if (index < 0 && this._slides.length) index = 0;
			this._selectedIndex = index;
			this._value = index < 0 ? "" : this._slides[index].value;
			this._panels.forEach((panel, slideIndex) => {
				panel.hidden = slideIndex !== index;
			});
			this._dots.forEach((dot, slideIndex) => {
				if (slideIndex === index) dot.setAttribute("aria-current", "true");
				else dot.removeAttribute("aria-current");
			});
			this._controls.hidden = this._slides.length < 2;
			this._previous.disabled = index < 1 && !this.loop;
			this._next.disabled = (index < 0 || index >= this._slides.length - 1) && !this.loop;
			this._status.textContent = index < 0 ? "No slides" : `${index + 1} of ${this._slides.length}: ${this._slides[index].label}`;
		}
		_selectIndex(index) {
			if (index < 0 || index >= this._slides.length || index === this._selectedIndex) return;
			this.value = this._slides[index].value;
			this.dispatchEvent(new Event("change", {
				bubbles: true,
				composed: true
			}));
		}
		_onKeyDown(event) {
			if (event.target !== this._viewport) return;
			if (event.key === "ArrowRight") {
				event.preventDefault();
				this.next();
			} else if (event.key === "ArrowLeft") {
				event.preventDefault();
				this.previous();
			} else if (event.key === "Home") {
				event.preventDefault();
				this._selectIndex(0);
			} else if (event.key === "End") {
				event.preventDefault();
				this._selectIndex(this._slides.length - 1);
			}
		}
		_onPointerDown(event) {
			if (event.pointerType === "mouse") return;
			this._pointerStart = {
				x: event.clientX,
				y: event.clientY
			};
			this._viewport.setPointerCapture?.(event.pointerId);
		}
		_onPointerUp(event) {
			if (!this._pointerStart) return;
			const dx = event.clientX - this._pointerStart.x;
			const dy = event.clientY - this._pointerStart.y;
			this._pointerStart = null;
			if (Math.abs(dx) < 50 || Math.abs(dx) <= Math.abs(dy) * 1.2) return;
			if (dx < 0) this.next();
			else this.previous();
		}
	};
	function defineIconGridView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit IconGridView requires a browser with Custom Elements");
		const existing = registry.get(ICONGRIDVIEW_TAG_NAME);
		if (existing && existing !== BiscuitIconGridView) throw new Error(`<${ICONGRIDVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(ICONGRIDVIEW_TAG_NAME, BiscuitIconGridView);
		return BiscuitIconGridView;
	}
	function defineFileBrowserView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit FileBrowserView requires a browser with Custom Elements");
		const existing = registry.get(FILEBROWSERVIEW_TAG_NAME);
		if (existing && existing !== BiscuitFileBrowserView) throw new Error(`<${FILEBROWSERVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(FILEBROWSERVIEW_TAG_NAME, BiscuitFileBrowserView);
		return BiscuitFileBrowserView;
	}
	function defineImageView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit ImageView requires a browser with Custom Elements");
		const existing = registry.get(IMAGEVIEW_TAG_NAME);
		if (existing && existing !== BiscuitImageView) throw new Error(`<${IMAGEVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(IMAGEVIEW_TAG_NAME, BiscuitImageView);
		return BiscuitImageView;
	}
	function defineCarouselView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit CarouselView requires a browser with Custom Elements");
		const existing = registry.get(CAROUSELVIEW_TAG_NAME);
		if (existing && existing !== BiscuitCarouselView) throw new Error(`<${CAROUSELVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(CAROUSELVIEW_TAG_NAME, BiscuitCarouselView);
		return BiscuitCarouselView;
	}
	function createIconGridView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit IconGridView requires a browser document");
		defineIconGridView();
		const element = createConfiguredElement(ICONGRIDVIEW_TAG_NAME, options);
		for (const key of [
			"heading",
			"icons",
			"showLabels",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		return element;
	}
	function createFileBrowserView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit FileBrowserView requires a browser document");
		defineFileBrowserView();
		const element = createConfiguredElement(FILEBROWSERVIEW_TAG_NAME, options);
		for (const key of [
			"label",
			"items",
			"searchable",
			"value",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		return element;
	}
	function createImageView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit ImageView requires a browser document");
		defineImageView();
		const element = createConfiguredElement(IMAGEVIEW_TAG_NAME, options);
		for (const name of [
			"alt",
			"fit",
			"aspectRatio",
			"caption",
			"fallback",
			"loading",
			"srcset",
			"sizes",
			"theme"
		]) if (Object.hasOwn(options, name)) element[name] = options[name];
		if (Object.hasOwn(options, "src")) element.src = options.src;
		return element;
	}
	function createCarouselView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit CarouselView requires a browser document");
		defineCarouselView();
		const element = createConfiguredElement(CAROUSELVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "loop")) element.loop = options.loop;
		if (Object.hasOwn(options, "ariaLabel")) element.setAttribute("aria-label", String(options.ariaLabel));
		for (const item of options.slides ?? []) {
			const slide = document.createElement("biscuit-slide");
			if (Object.hasOwn(item, "value")) slide.setAttribute("value", String(item.value));
			if (Object.hasOwn(item, "label")) slide.setAttribute("label", String(item.label));
			if (item.selected) slide.setAttribute("selected", "");
			if (item.content != null) slide.append(item.content);
			element.append(slide);
		}
		if (Object.hasOwn(options, "value")) element.value = options.value;
		element._syncSlides();
		return element;
	}
	function defineMedia() {
		defineIconGridView();
		defineFileBrowserView();
		defineImageView();
		defineCarouselView();
	}
	// src/groups/code.js
	var code_exports = /* @__PURE__ */ __exportAll({
		BiscuitCodeExampleView: () => BiscuitCodeExampleView,
		BiscuitCodeView: () => BiscuitCodeView,
		BiscuitCopyButtonView: () => BiscuitCopyButtonView,
		BiscuitSuggestionView: () => BiscuitSuggestionView,
		createCodeExampleView: () => createCodeExampleView,
		createCodeView: () => createCodeView,
		createCopyButtonView: () => createCopyButtonView,
		createSuggestionView: () => createSuggestionView,
		defineCode: () => defineCode,
		defineCodeExampleView: () => defineCodeExampleView,
		defineCodeView: () => defineCodeView,
		defineCopyButtonView: () => defineCopyButtonView,
		defineSuggestionView: () => defineSuggestionView,
		detectCodeLanguage: () => detectCodeLanguage,
		registerCodeCompletionProvider: () => registerCodeCompletionProvider,
		registerCodeLanguage: () => registerCodeLanguage,
		tokenizeCode: () => tokenizeCode
	});
	var CODEVIEW_TAG_NAME = "biscuit-codeview";
	var CODEVIEW_LARGE_FILE_LINE_LIMIT = 5e4;
	var JAVASCRIPT_COMPLETION_CONTEXT_CHARS = 32768;
	var CODEEXAMPLEVIEW_TAG_NAME = "biscuit-codeexampleview";
	var COPYBUTTONVIEW_TAG_NAME = "biscuit-copybuttonview";
	var SUGGESTIONVIEW_TAG_NAME = "biscuit-suggestionview";
	var codeExampleViewId = 0;
	var codeCompletionId = 0;
	var codeLargeWarningId = 0;
	var codeLanguages = /* @__PURE__ */ new Map();
	var codeLanguageAliases = /* @__PURE__ */ new Map();
	var codeLanguageListeners = /* @__PURE__ */ new Set();
	var codeCompletionProviders = /* @__PURE__ */ new Map();
	function pushCodeToken(tokens, type, value) {
		if (!value) return;
		const previous = tokens[tokens.length - 1];
		if (previous?.type === type) previous.text += value;
		else tokens.push({
			type,
			text: value
		});
	}
	function registerCodeLanguage(name, definition) {
		const key = String(name).toLowerCase();
		if (!/^[a-z][a-z0-9-]*$/.test(key)) throw new TypeError("Invalid code language name");
		if (!definition || !Array.isArray(definition.rules) && typeof definition.tokenizeLine !== "function") throw new TypeError("A code language needs rules or tokenizeLine");
		const aliases = [key, ...(definition.aliases ?? []).map((alias) => String(alias).toLowerCase())];
		for (const alias of aliases) if (!/^[a-z][a-z0-9-]*$/.test(alias) || codeLanguageAliases.has(alias)) throw new Error("Code language name or alias already registered: " + alias);
		const rules = definition.rules?.map(({ type, pattern }) => {
			if (!/^[a-z][a-z0-9-]*$/.test(type) || !(pattern instanceof RegExp)) throw new TypeError("Code rules need a type and RegExp pattern");
			const flags = pattern.flags.replace(/[gy]/g, "");
			const matcher = new RegExp("^(?:" + pattern.source + ")", flags);
			if (matcher.test("")) throw new TypeError("Code rules cannot match empty text");
			return {
				type,
				matcher
			};
		});
		codeLanguages.set(key, {
			initialState: definition.initialState ?? "",
			tokenizeLine: definition.tokenizeLine,
			rules,
			detect: definition.detect
		});
		for (const alias of aliases) codeLanguageAliases.set(alias, key);
		for (const listener of codeLanguageListeners) listener();
	}
	function resolveCodeLanguage(language) {
		return codeLanguageAliases.get(String(language ?? "plain").toLowerCase()) ?? "plain";
	}
	function scanCodeLine(line, language, state = "") {
		const definition = codeLanguages.get(resolveCodeLanguage(language));
		if (!definition) return {
			tokens: line ? [{
				type: "text",
				text: line
			}] : [],
			state: ""
		};
		let result;
		if (definition.tokenizeLine) result = definition.tokenizeLine(line, state);
		else {
			const tokens = [];
			let index = 0;
			while (index < line.length) {
				let matched = false;
				for (const rule of definition.rules) {
					const match = rule.matcher.exec(line.slice(index));
					if (!match) continue;
					pushCodeToken(tokens, rule.type, match[0]);
					index += match[0].length;
					matched = true;
					break;
				}
				if (!matched) pushCodeToken(tokens, "text", line[index++]);
			}
			result = {
				tokens,
				state
			};
		}
		if (!result || !Array.isArray(result.tokens) || result.tokens.map((token) => token.text).join("") !== line) throw new Error("Code language tokenizer must preserve each line exactly");
		return {
			tokens: result.tokens,
			state: result.state ?? ""
		};
	}
	function detectCodeLanguage(source) {
		const sample = String(source ?? "").slice(0, 65536);
		if (!sample.trim()) return "plain";
		let best = "plain";
		let bestScore = 0;
		let nextScore = 0;
		for (const [name, definition] of codeLanguages) {
			if (!definition.detect || name === "plain") continue;
			let score = 0;
			try {
				score = Number(definition.detect(sample)) || 0;
			} catch {}
			if (score > bestScore) {
				nextScore = bestScore;
				bestScore = score;
				best = name;
			} else if (score > nextScore) nextScore = score;
		}
		return bestScore >= .65 && bestScore - nextScore >= .15 ? best : "plain";
	}
	function tokenizeCode(source, language = "plain") {
		const value = String(source ?? "");
		const selected = String(language).toLowerCase() === "auto" ? detectCodeLanguage(value) : language;
		const tokens = [];
		let state = codeLanguages.get(resolveCodeLanguage(selected))?.initialState ?? "";
		let start = 0;
		while (start < value.length) {
			let end = start;
			while (end < value.length && value[end] !== "\n" && value[end] !== "\r") end++;
			const result = scanCodeLine(value.slice(start, end), selected, state);
			for (const token of result.tokens) pushCodeToken(tokens, token.type, token.text);
			state = result.state;
			const breakEnd = value[end] === "\r" && value[end + 1] === "\n" ? end + 2 : Math.min(end + 1, value.length);
			pushCodeToken(tokens, "text", value.slice(end, breakEnd));
			start = breakEnd;
		}
		return tokens;
	}
	var javascriptWords = new Set("async await break case catch class const continue debugger default delete do else export extends false finally for from function get if import in instanceof let new null of return set static super switch this throw true try typeof undefined var void while yield".split(" "));
	var javaWords = new Set("abstract assert boolean break byte case catch char class const continue default do double else enum extends false final finally float for if implements import instanceof int interface long native new null package private protected public record return sealed permits short static strictfp super switch synchronized this throw throws transient true try var void volatile while yield".split(" "));
	function consumeCodeQuote(line, index, quote) {
		let cursor = index + quote.length;
		while (cursor < line.length) {
			if (line[cursor] === "\\") {
				cursor += 2;
				continue;
			}
			if (line.startsWith(quote, cursor)) return {
				end: cursor + quote.length,
				closed: true
			};
			cursor++;
		}
		return {
			end: line.length,
			closed: false
		};
	}
	function scanScriptCodeLine(line, previousState, java = false) {
		const tokens = [];
		let index = 0;
		let state = previousState || "";
		while (index < line.length) {
			if (state === "comment") {
				const end = line.indexOf("*/", index);
				const stop = end < 0 ? line.length : end + 2;
				pushCodeToken(tokens, "comment", line.slice(index, stop));
				index = stop;
				if (end >= 0) state = "";
				continue;
			}
			if (state.startsWith("quote:")) {
				const quote = state.slice(6);
				const result = consumeCodeQuote(line, index - quote.length, quote);
				const stop = Math.max(index, result.end);
				pushCodeToken(tokens, "string", line.slice(index, stop));
				index = stop;
				if (result.closed) state = "";
				continue;
			}
			if (line.startsWith("//", index)) {
				pushCodeToken(tokens, "comment", line.slice(index));
				break;
			}
			if (line.startsWith("/*", index)) {
				state = "comment";
				continue;
			}
			const quote = java && line.startsWith("\"\"\"", index) ? "\"\"\"" : line[index];
			if (quote === "'" || quote === "\"" || !java && quote === "`" || quote === "\"\"\"") {
				const result = consumeCodeQuote(line, index, quote);
				pushCodeToken(tokens, "string", line.slice(index, result.end));
				index = result.end;
				if (!result.closed && (quote === "`" || quote === "\"\"\"" || line.endsWith("\\"))) state = "quote:" + quote;
				continue;
			}
			const number = /^(?:0[xX][\da-fA-F]+|0[bB][01]+|\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)[fFdDlLnN]?/.exec(line.slice(index));
			if (number) {
				pushCodeToken(tokens, "number", number[0]);
				index += number[0].length;
				continue;
			}
			const identifier = /^[A-Za-z_$][\w$]*/.exec(line.slice(index));
			if (identifier) {
				pushCodeToken(tokens, (java ? javaWords : javascriptWords).has(identifier[0]) ? "keyword" : "text", identifier[0]);
				index += identifier[0].length;
				continue;
			}
			pushCodeToken(tokens, /[+*\/%=&|!<>?:~-]/.test(line[index]) ? "operator" : "text", line[index++]);
		}
		return {
			tokens,
			state
		};
	}
	function scanCssCodeLine(line, previousState) {
		const tokens = [];
		const pieces = (previousState || "0|0|0").split("|");
		let comment = pieces[0] === "1";
		let depth = Number(pieces[1]) || 0;
		let value = pieces[2] === "1";
		let quote = pieces[3] || "";
		let index = 0;
		while (index < line.length) {
			if (comment) {
				const end = line.indexOf("*/", index);
				const stop = end < 0 ? line.length : end + 2;
				pushCodeToken(tokens, "comment", line.slice(index, stop));
				index = stop;
				if (end >= 0) comment = false;
				continue;
			}
			if (quote) {
				const result = consumeCodeQuote(line, index - 1, quote);
				pushCodeToken(tokens, "string", line.slice(index, result.end));
				index = result.end;
				if (result.closed) quote = "";
				continue;
			}
			if (line.startsWith("/*", index)) {
				comment = true;
				continue;
			}
			if (line[index] === "'" || line[index] === "\"") {
				const result = consumeCodeQuote(line, index, line[index]);
				pushCodeToken(tokens, "string", line.slice(index, result.end));
				quote = result.closed ? "" : line[index];
				index = result.end;
				continue;
			}
			if (line[index] === "{") {
				pushCodeToken(tokens, "operator", "{");
				depth++;
				value = false;
				index++;
				continue;
			}
			if (line[index] === "}") {
				pushCodeToken(tokens, "operator", "}");
				depth = Math.max(0, depth - 1);
				value = false;
				index++;
				continue;
			}
			if (line[index] === ":") {
				pushCodeToken(tokens, "operator", ":");
				if (depth) value = true;
				index++;
				continue;
			}
			if (line[index] === ";") {
				pushCodeToken(tokens, "operator", ";");
				value = false;
				index++;
				continue;
			}
			const at = /^@[\w-]+/.exec(line.slice(index));
			if (at) {
				pushCodeToken(tokens, "at-rule", at[0]);
				index += at[0].length;
				continue;
			}
			const word = /^(?:--[\w-]+|[\w.-]+)/.exec(line.slice(index));
			if (word) {
				pushCodeToken(tokens, !depth ? "selector" : value ? /^\d/.test(word[0]) ? "number" : "text" : "property", word[0]);
				index += word[0].length;
				continue;
			}
			pushCodeToken(tokens, !depth ? "selector" : "text", line[index++]);
		}
		return {
			tokens,
			state: [
				comment ? 1 : 0,
				depth,
				value ? 1 : 0,
				quote
			].join("|")
		};
	}
	function scanHtmlCodeLine(line, previousState) {
		const tokens = [];
		const state = previousState ? JSON.parse(previousState) : {
			mode: "text",
			tag: "",
			quote: "",
			embedded: ""
		};
		let index = 0;
		while (index < line.length) {
			if (state.mode === "script" || state.mode === "style") {
				const match = new RegExp("</" + state.mode + "\\b", "i").exec(line.slice(index));
				const stop = match ? index + match.index : line.length;
				const language = state.mode === "script" ? "javascript" : "css";
				const result = scanCodeLine(line.slice(index, stop), language, state.embedded);
				for (const token of result.tokens) pushCodeToken(tokens, token.type, token.text);
				state.embedded = result.state;
				index = stop;
				if (!match) break;
				state.mode = "text";
				state.embedded = "";
				continue;
			}
			if (state.mode === "comment") {
				const end = line.indexOf("-->", index);
				const stop = end < 0 ? line.length : end + 3;
				pushCodeToken(tokens, "comment", line.slice(index, stop));
				index = stop;
				if (end >= 0) state.mode = "text";
				continue;
			}
			if (state.mode === "tag") {
				if (state.quote) {
					const result = consumeCodeQuote(line, index - 1, state.quote);
					pushCodeToken(tokens, "string", line.slice(index, result.end));
					index = result.end;
					if (result.closed) state.quote = "";
					continue;
				}
				const character = line[index];
				if (character === "'" || character === "\"") {
					const result = consumeCodeQuote(line, index, character);
					pushCodeToken(tokens, "string", line.slice(index, result.end));
					state.quote = result.closed ? "" : character;
					index = result.end;
				} else if (character === ">") {
					pushCodeToken(tokens, "operator", ">");
					index++;
					state.mode = state.tag === "script" || state.tag === "style" ? state.tag : "text";
					state.tag = "";
				} else {
					const attr = /^[\w:-]+/.exec(line.slice(index));
					if (attr) {
						pushCodeToken(tokens, "attribute", attr[0]);
						index += attr[0].length;
					} else {
						pushCodeToken(tokens, /[=\/]/.test(character) ? "operator" : "text", character);
						index++;
					}
				}
				continue;
			}
			if (line.startsWith("<!--", index)) {
				state.mode = "comment";
				continue;
			}
			const tag = /^<(\/)?([A-Za-z][\w:-]*)/.exec(line.slice(index));
			if (tag) {
				pushCodeToken(tokens, "operator", tag[1] ? "</" : "<");
				pushCodeToken(tokens, "tag", tag[2]);
				state.mode = "tag";
				state.tag = tag[1] ? "" : tag[2].toLowerCase();
				index += tag[0].length;
				continue;
			}
			pushCodeToken(tokens, "text", line[index++]);
		}
		return {
			tokens,
			state: JSON.stringify(state)
		};
	}
	registerCodeLanguage("plain", { rules: [] });
	registerCodeLanguage("javascript", {
		aliases: ["js"],
		tokenizeLine: (line, state) => scanScriptCodeLine(line, state),
		detect: (sample) => /\b(?:const|let|function|export|import)\b|=>/.test(sample) ? .82 : 0
	});
	registerCodeLanguage("java", {
		tokenizeLine: (line, state) => scanScriptCodeLine(line, state, true),
		detect: (sample) => /\b(?:package\s+[\w.]+;|public\s+(?:static\s+)?(?:class|void)|System\.out\.)/.test(sample) ? .9 : 0
	});
	registerCodeLanguage("css", {
		tokenizeLine: scanCssCodeLine,
		detect: (sample) => /(?:^|})\s*[^{};]+\{\s*[-\w]+\s*:/.test(sample) ? .84 : 0
	});
	registerCodeLanguage("html", {
		aliases: ["htm"],
		tokenizeLine: scanHtmlCodeLine,
		detect: (sample) => /<!doctype\s+html|<\/?[a-z][\w:-]*(?:\s|>)/i.test(sample) ? .95 : 0
	});
	function registerCodeCompletionProvider(language, provider) {
		const name = String(language).toLowerCase();
		if (!/^[a-z][a-z0-9-]*$/.test(name) || typeof provider !== "function") throw new TypeError("A completion provider needs a language name and function");
		const key = codeLanguageAliases.get(name) ?? name;
		if (!codeCompletionProviders.has(key)) codeCompletionProviders.set(key, /* @__PURE__ */ new Set());
		codeCompletionProviders.get(key).add(provider);
		return () => {
			const providers = codeCompletionProviders.get(key);
			providers?.delete(provider);
			if (!providers?.size) codeCompletionProviders.delete(key);
		};
	}
	var javascriptGlobals = [
		[
			"console",
			"var",
			"Browser console"
		],
		[
			"document",
			"var",
			"Current document"
		],
		[
			"window",
			"var",
			"Browser window"
		],
		[
			"navigator",
			"var",
			"Browser information"
		],
		[
			"location",
			"var",
			"Current URL"
		],
		[
			"history",
			"var",
			"Navigation history"
		],
		[
			"localStorage",
			"var",
			"Persistent storage"
		],
		[
			"sessionStorage",
			"var",
			"Session storage"
		],
		[
			"fetch",
			"fn",
			"HTTP request"
		],
		[
			"setTimeout",
			"fn",
			"Schedule a callback"
		],
		[
			"setInterval",
			"fn",
			"Repeat a callback"
		],
		[
			"requestAnimationFrame",
			"fn",
			"Next animation frame"
		],
		[
			"addEventListener",
			"fn",
			"Listen for an event"
		],
		[
			"URL",
			"class",
			"URL parser"
		],
		[
			"URLSearchParams",
			"class",
			"Query parameters"
		],
		[
			"HTMLElement",
			"class",
			"HTML element"
		],
		[
			"Promise",
			"class",
			"Async result"
		],
		[
			"Array",
			"class",
			"Array constructor"
		],
		[
			"Object",
			"class",
			"Object constructor"
		],
		[
			"Map",
			"class",
			"Keyed collection"
		],
		[
			"Set",
			"class",
			"Unique values"
		],
		[
			"Math",
			"var",
			"Math utilities"
		],
		[
			"JSON",
			"var",
			"JSON utilities"
		]
	].map(([label, kind, detail]) => ({
		label,
		kind,
		detail,
		insertText: label
	}));
	var javascriptMembers = Object.fromEntries(Object.entries({
		console: {
			log: "Log a message",
			error: "Log an error",
			warn: "Log a warning",
			info: "Log information",
			table: "Show a table",
			time: "Start a timer",
			timeEnd: "End a timer",
			clear: "Clear the console"
		},
		document: {
			querySelector: "Find the first match",
			querySelectorAll: "Find all matches",
			getElementById: "Find an ID",
			createElement: "Create an element",
			addEventListener: "Listen for an event",
			body: "Document body",
			title: "Page title",
			activeElement: "Focused element"
		},
		window: {
			document: "Current document",
			location: "Current URL",
			localStorage: "Persistent storage",
			setTimeout: "Schedule a callback",
			requestAnimationFrame: "Next animation frame",
			addEventListener: "Listen for an event",
			innerWidth: "Viewport width",
			innerHeight: "Viewport height"
		},
		navigator: {
			language: "Preferred language",
			languages: "Preferred languages",
			clipboard: "Clipboard API",
			onLine: "Connection state",
			userAgent: "Browser identifier"
		},
		location: {
			href: "Current URL",
			pathname: "URL path",
			search: "Query string",
			hash: "URL fragment",
			assign: "Navigate",
			replace: "Replace history entry"
		},
		localStorage: {
			getItem: "Read a value",
			setItem: "Write a value",
			removeItem: "Remove a value",
			clear: "Clear storage",
			length: "Number of values"
		},
		sessionStorage: {
			getItem: "Read a value",
			setItem: "Write a value",
			removeItem: "Remove a value",
			clear: "Clear storage",
			length: "Number of values"
		},
		Math: {
			abs: "Absolute value",
			floor: "Round down",
			ceil: "Round up",
			round: "Round to integer",
			max: "Largest value",
			min: "Smallest value",
			random: "Random number",
			PI: "Pi"
		},
		JSON: {
			parse: "Parse JSON",
			stringify: "Create JSON"
		},
		Array: {
			from: "Create from iterable",
			isArray: "Check array",
			of: "Create array"
		},
		Object: {
			keys: "Own keys",
			values: "Own values",
			entries: "Own entries",
			assign: "Copy properties",
			fromEntries: "Create from entries"
		},
		Promise: {
			resolve: "Resolved promise",
			reject: "Rejected promise",
			all: "Wait for all",
			allSettled: "Wait for all results",
			race: "First result"
		}
	}).map(([owner, entries]) => [owner, Object.entries(entries).map(([label, detail]) => ({
		label,
		kind: /^(?:body|title|activeElement|document|location|localStorage|innerWidth|innerHeight|language|languages|clipboard|onLine|userAgent|href|pathname|search|hash|length|PI)$/.test(label) ? "prop" : "fn",
		detail,
		insertText: label
	}))]));
	var javascriptKeywords = [
		"async",
		"await",
		"class",
		"const",
		"continue",
		"else",
		"export",
		"for",
		"function",
		"if",
		"import",
		"let",
		"new",
		"return",
		"throw",
		"try",
		"var",
		"while"
	].map((label) => ({
		label,
		kind: "kw",
		detail: "JavaScript keyword",
		insertText: label + (/^(?:async|const|export|import|let|new|return|throw|var)$/.test(label) ? " " : "")
	}));
	function javascriptCompletionProvider(context) {
		let start = Math.max(0, context.offset - JAVASCRIPT_COMPLETION_CONTEXT_CHARS);
		if (start) {
			const nextLine = context.value.indexOf("\n", start);
			if (nextLine !== -1 && nextLine < context.offset) start = nextLine + 1;
		}
		const tokens = tokenizeCode(context.value.slice(start, context.offset), "javascript");
		if (["comment", "string"].includes(tokens.at(-1)?.type)) return [];
		const source = tokens.map((token) => ["comment", "string"].includes(token.type) ? token.text.replace(/[^\n]/g, " ") : token.text).join("");
		const member = /([A-Za-z_$][\w$]*)\s*(?:\?\.|\.)\s*([A-Za-z_$][\w$]*)?$/.exec(source);
		const prefix = member ? member[2] ?? "" : context.prefix;
		if (!member && prefix.length < 2) return [];
		let candidates;
		if (member) {
			candidates = javascriptMembers[member[1]] ?? [];
			if (!candidates.length) {
				const escaped = member[1].replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
				const object = new RegExp("\\b(?:const|let|var)\\s+" + escaped + "\\s*=\\s*\\{([^}]*)\\}").exec(source);
				candidates = Array.from(object?.[1]?.matchAll(/(?:^|,)\s*([A-Za-z_$][\w$]*)\s*(?::|\()/g) ?? [], (match) => ({
					label: match[1],
					kind: "prop",
					detail: "Local object",
					insertText: match[1]
				}));
			}
		} else {
			const locals = /* @__PURE__ */ new Map();
			for (const match of source.matchAll(/\b(const|let|var|function|class)\s+([A-Za-z_$][\w$]*)/g)) locals.set(match[2], {
				label: match[2],
				kind: match[1] === "function" ? "fn" : match[1] === "class" ? "class" : "var",
				detail: "Local declaration",
				insertText: match[2]
			});
			candidates = [
				...locals.values(),
				...javascriptGlobals,
				...javascriptKeywords
			];
		}
		const seen = /* @__PURE__ */ new Set();
		return candidates.filter((item) => {
			if (item.label === prefix || !item.label.startsWith(prefix) || seen.has(item.label)) return false;
			seen.add(item.label);
			return true;
		}).slice(0, 12);
	}
	registerCodeCompletionProvider("javascript", javascriptCompletionProvider);
	var codeViewStyles = `
  :host {
    box-sizing: border-box;
    display: block;
    width: 100%;
    height: var(--biscuit-codeview-height, 19rem);
    min-width: 12rem;
    min-height: 7rem;
    overflow: hidden;
    color-scheme: inherit;
    border: var(--biscuit-codeview-border, 1px solid var(--biscuit-border-color, var(--biscuit-theme-textview-border-color, light-dark(#a5a8b3, #656b7a))));
    border-radius: var(--biscuit-codeview-radius, 0.55rem);
    background: var(--biscuit-codeview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-codeview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: var(--biscuit-codeview-font, 400 0.875rem/1.55 ui-monospace, SFMono-Regular, Consolas, monospace);
    --_code-line-height: 1.55em;
    --_code-gutter-width: 3.5rem;
    --_code-keyword: light-dark(#7245ac, #c6a4ff);
    --_code-string: light-dark(#16734d, #9cddb8);
    --_code-comment: light-dark(#687383, #9aa4b5);
    --_code-number: light-dark(#a04f1f, #f2ad7b);
    --_code-tag: light-dark(#a33954, #ff9ba9);
    --_code-attribute: light-dark(#795600, #f2d186);
    --_code-property: light-dark(#2b65a0, #96c8ff);
    --_code-selector: light-dark(#8a4ca7, #d4a5eb);
  }
  ${themeStyles}
  :host([experimental-completion]) { overflow: visible; }
  :host([theme="deep-dark"]) {
    --_code-keyword: #fff; --_code-string: #fff; --_code-comment: #aaa;
    --_code-number: #fff; --_code-tag: #fff; --_code-attribute: #ccc;
    --_code-property: #fff; --_code-selector: #ccc;
  }
  :host([resizable]) { resize: both; overflow: hidden; }
  :host([resizable][limit-resize-to-parent]) {
    max-width: var(--biscuit-codeview-parent-max-width, 100%);
    max-height: var(--biscuit-codeview-parent-max-height, 100%);
  }
  :host(:focus-within) {
    outline: 2px solid var(--biscuit-codeview-focus-color, var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))));
    outline-offset: 2px;
  }
  [hidden] { display: none !important; }
  [part="frame"] { position: relative; display: flex; flex-direction: column; width: 100%; height: 100%; min-height: 0; }
  [part="suggestions"] { position: absolute; z-index: 5; width: min(24rem, calc(100% - 0.75rem)); max-height: 11rem; }
  [part="toolbar"] {
    flex: none; display: flex; justify-content: space-between; align-items: center; gap: 0.75rem;
    padding: 0.35rem 0.5rem; border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#e2e2e9, #454a56)));
  }
  [part="toolbar-start"] { display: flex; align-items: center; flex: 1; min-width: 0; }
  [part="toolbar-start"]::slotted(*) { max-width: 100%; }
  [part="workspace"] { position: relative; flex: 1; min-height: 0; overflow: hidden; }
  [part="highlight"], [part="gutter"] {
    position: absolute; top: 0; bottom: 0; overflow: hidden; pointer-events: none;
    font: inherit; line-height: var(--_code-line-height); white-space: pre;
  }
  [part="highlight"] { left: var(--_code-gutter-width); right: 0; color: inherit; }
  [part="line-probe"] {
    position: absolute; left: 0; top: 0; visibility: hidden; pointer-events: none;
    font: inherit; line-height: var(--_code-line-height); white-space: pre;
  }
  [part="gutter"] {
    box-sizing: border-box; left: 0; width: var(--_code-gutter-width);
    padding: 0;
    background: var(--biscuit-codeview-gutter-background, color-mix(in srgb, currentColor 5%, transparent));
    color: var(--biscuit-codeview-gutter-color, light-dark(#828695, #a4a9b7));
    text-align: right;
    border-right: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#e2e2e9, #454a56)));
  }
  :host([line-numbers="false"]) { --_code-gutter-width: 0px; }
  :host([line-numbers="false"]) [part="gutter"] { display: none; }
  :host([line-numbers-container-position="right"]) [part="highlight"] { left: 0; right: var(--_code-gutter-width); }
  :host([line-numbers-container-position="right"]) [part="gutter"] {
    left: auto; right: 0;
    border-right: 0; border-left: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#e2e2e9, #454a56)));
  }
  :host([line-number-position="left"]) [part="gutter"] { text-align: left; }
  [part="lines"], [part="numbers"] { position: absolute; left: 0; top: 0; min-width: 100%; will-change: transform; }
  /* Absolute rows ignore their parent's padding on the top edge. Move the
     layers themselves so the painted text meets the textarea caret. */
  [part="lines"] { top: 0.6rem; padding: 0 0.75rem; box-sizing: border-box; }
  [part="numbers"] { top: 0.6rem; }
  [part="code-line"], [part="line-number"] { display: block; min-height: var(--_code-line-height); line-height: var(--_code-line-height); }
  [part="line-number"] { box-sizing: border-box; left: 0; right: 0; padding: 0 0.65rem; text-align: inherit; }
  [part="code-line"] { white-space: pre; tab-size: var(--_code-tab-size, 2); }
  :host([wrap]:not([data-large-file-mode])) [part="code-line"] { white-space: pre-wrap; overflow-wrap: anywhere; }
  [part="editor"] {
    box-sizing: border-box; position: absolute; inset: 0; display: block; width: 100%; height: 100%;
    margin: 0; padding: 0.6rem 0.75rem 0.6rem calc(var(--_code-gutter-width) + 0.75rem);
    border: 0; outline: 0; resize: none; background: transparent; color: transparent;
    caret-color: var(--biscuit-codeview-caret-color, var(--biscuit-codeview-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: inherit; line-height: var(--_code-line-height); letter-spacing: inherit;
    tab-size: var(--_code-tab-size, 2); white-space: pre; overflow: auto;
  }
  :host([wrap]:not([data-large-file-mode])) [part="editor"] { white-space: pre-wrap; overflow-wrap: anywhere; }
  [part="editor"]::selection { background: var(--biscuit-codeview-selection, light-dark(#a9c7ff99, #6b63db99)); color: transparent; }
  :host([line-numbers-container-position="right"]) [part="editor"] {
    padding-left: 0.75rem; padding-right: calc(var(--_code-gutter-width) + 0.75rem);
  }
  [part="code-token"][data-type="keyword"] { color: var(--biscuit-codeview-keyword, var(--_code-keyword)); }
  [part="code-token"][data-type="string"] { color: var(--biscuit-codeview-string, var(--_code-string)); }
  [part="code-token"][data-type="comment"] { color: var(--biscuit-codeview-comment, var(--_code-comment)); }
  [part="code-token"][data-type="number"] { color: var(--biscuit-codeview-number, var(--_code-number)); }
  [part="code-token"][data-type="tag"] { color: var(--biscuit-codeview-tag, var(--_code-tag)); }
  [part="code-token"][data-type="attribute"] { color: var(--biscuit-codeview-attribute, var(--_code-attribute)); }
  [part="code-token"][data-type="property"] { color: var(--biscuit-codeview-property, var(--_code-property)); }
  [part="code-token"][data-type="selector"], [part="code-token"][data-type="at-rule"] { color: var(--biscuit-codeview-selector, var(--_code-selector)); }
  [part="code-token"][data-type="operator"] { color: var(--biscuit-codeview-operator, inherit); }
  [part="large-file-status"] {
    flex: none; padding: 0.3rem 0.65rem; border-bottom: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#e2e2e9, #454a56)));
    background: var(--biscuit-codeview-gutter-background, color-mix(in srgb, currentColor 5%, transparent));
    font: 600 0.75rem/1.4 system-ui, sans-serif;
  }
  [part="large-file-warning"] { position: absolute; inset: 0; z-index: 10; display: grid; place-items: center; padding: 0.75rem; background: rgb(0 0 0 / 55%); }
  [part="large-file-dialog"] {
    box-sizing: border-box; width: min(100%, 30rem); max-height: 100%; overflow: auto; padding: 1rem;
    border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    border-radius: 0.65rem; background: var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37)));
    color: var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8)));
    box-shadow: 0 14px 40px rgb(0 0 0 / 30%); font: 400 0.875rem/1.45 system-ui, sans-serif;
  }
  [part="large-file-title"] { margin: 0 0 0.35rem; font-size: 1rem; font-weight: 750; }
  [part="large-file-message"] { margin: 0 0 0.9rem; }
  [part="large-file-actions"] { display: flex; flex-wrap: wrap; gap: 0.45rem; justify-content: flex-end; }
  [part="large-file-actions"] button {
    min-height: 2rem; padding: 0.35rem 0.65rem; border: 1px solid currentColor; border-radius: 0.35rem;
    background: transparent; color: inherit; font: inherit; font-weight: 650; cursor: pointer;
  }
  [part="large-file-actions"] button:hover, [part="large-file-actions"] button:focus-visible {
    background: var(--biscuit-theme-option-background, light-dark(#efedff, #45415f));
    color: var(--biscuit-theme-option-color, light-dark(#38306e, #fff));
  }
`;
	var codeExampleViewStyles = `
  :host { display: block; min-width: 0; color-scheme: inherit; font: 600 0.72rem/1.3 system-ui, sans-serif; }
  ${themeStyles}
  [hidden] { display: none !important; }
  [part="controls"] { display: flex; justify-content: flex-end; }
  [part="toggle"] {
    display: inline-flex; align-items: center; gap: 0.35rem;
    min-height: 1.65rem; padding: 0.2rem 0.5rem;
    border: 1px solid var(--biscuit-codeexampleview-border-color, var(--biscuit-border-color, var(--biscuit-theme-textview-border-color, light-dark(#a5a8b3, #656b7a))));
    border-radius: var(--biscuit-codeexampleview-radius, 0.35rem);
    background: var(--biscuit-codeexampleview-background, var(--biscuit-surface, var(--biscuit-theme-surface, light-dark(#fff, #292c37))));
    color: var(--biscuit-codeexampleview-color, var(--biscuit-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8))));
    font: inherit; cursor: pointer;
  }
  [part="toggle"]::after { content: "⌄"; display: inline-block; font-size: 0.95rem; line-height: 0.7; transition: transform 120ms ease; }
  :host([open]) [part="toggle"]::after { transform: rotate(180deg); }
  [part="toggle"]:hover { filter: brightness(1.08); }
  [part="toggle"]:focus-visible { outline: 2px solid var(--biscuit-focus-color, var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff))); outline-offset: 2px; }
  [part="panel"] { min-width: 0; margin-top: 0.5rem; }
  [part="panel"]::slotted(biscuit-codeview) { width: 100%; }
`;
	var copyButtonViewStyles = `
  :host { display: inline-flex; color-scheme: inherit; font: 600 0.8rem/1.3 system-ui, sans-serif; }
  ${themeStyles}
  [part="button"] {
    display: inline-flex; align-items: center; gap: 0.4rem; min-height: 2rem;
    padding: 0.3rem 0.65rem; border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-textview-border-color, light-dark(#a5a8b3, #656b7a)));
    border-radius: 0.4rem; background: var(--biscuit-copybuttonview-background, var(--biscuit-theme-surface, light-dark(#fff, #292c37)));
    color: var(--biscuit-copybuttonview-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8)));
    font: inherit; cursor: pointer;
  }
  [part="button"]:hover:not(:disabled) { filter: brightness(1.08); }
  [part="button"]:focus-visible { outline: 2px solid var(--biscuit-theme-focus-color, light-dark(#6b63db, #aaa2ff)); outline-offset: 2px; }
  [part="button"]:disabled { opacity: 0.55; cursor: not-allowed; }
  [part="status"] { position: absolute; width: 1px; height: 1px; overflow: hidden; clip-path: inset(50%); white-space: nowrap; }
`;
	var suggestionViewStyles = `
  :host {
    display: block; width: min(28rem, 100%); max-height: var(--biscuit-suggestionview-max-height, 18rem);
    overflow: auto; box-sizing: border-box; color-scheme: inherit;
    border: 1px solid var(--biscuit-border-color, var(--biscuit-theme-cardview-border-color, light-dark(#d6d8df, #656b7a)));
    border-radius: 0.5rem; background: var(--biscuit-suggestionview-background, var(--biscuit-theme-surface, light-dark(#fff, #292c37)));
    color: var(--biscuit-suggestionview-color, var(--biscuit-theme-color, light-dark(#24252b, #f3f4f8)));
    box-shadow: 0 10px 26px rgb(0 0 0 / 17%); font: 0.84rem/1.45 system-ui, sans-serif;
  }
  ${themeStyles}
  :host([hidden]) { display: none !important; }
  [part="list"] { margin: 0; padding: 0.3rem; list-style: none; }
  [part="option"] {
    display: flex; align-items: center; gap: 0.6rem; width: 100%; padding: 0.38rem 0.5rem;
    border: 0; border-radius: 0.35rem; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer;
  }
  [part="option"][aria-selected="true"] { background: var(--biscuit-suggestionview-selected-background, var(--biscuit-theme-option-background, light-dark(#efedff, #45415f))); color: var(--biscuit-suggestionview-selected-color, var(--biscuit-theme-option-color, light-dark(#38306e, #fff))); }
  [part="kind"] { flex: none; min-width: 2.2rem; padding: 0.12rem 0.25rem; border: 1px solid currentColor; border-radius: 0.25rem; font: 650 0.65rem/1.2 ui-monospace, monospace; text-align: center; opacity: 0.75; }
  [part="label"] { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-family: ui-monospace, monospace; }
  [part="detail"] { flex: none; max-width: 40%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; opacity: 0.7; font-size: 0.75rem; }
  [part="empty"] { padding: 0.7rem; opacity: 0.7; }
`;
	var BiscuitCopyButtonView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["disabled", "label"];
		}
		constructor() {
			super();
			this.target = null;
			this._resetTimer = null;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = copyButtonViewStyles;
			this._button = document.createElement("button");
			this._button.type = "button";
			this._button.setAttribute("part", "button");
			this._status = document.createElement("span");
			this._status.setAttribute("part", "status");
			this._status.setAttribute("role", "status");
			this._button.addEventListener("click", () => {
				this.copy();
			});
			root.append(style, this._button, this._status);
			this._render();
		}
		connectedCallback() {
			this._render();
		}
		disconnectedCallback() {
			clearTimeout(this._resetTimer);
		}
		attributeChangedCallback() {
			this._render();
		}
		get label() {
			return this.getAttribute("label") ?? "Copy";
		}
		set label(value) {
			if (value == null) this.removeAttribute("label");
			else this.setAttribute("label", String(value));
		}
		get text() {
			return this.getAttribute("text") ?? "";
		}
		set text(value) {
			if (value == null) this.removeAttribute("text");
			else this.setAttribute("text", String(value));
		}
		get htmlFor() {
			return this.getAttribute("for") ?? "";
		}
		set htmlFor(value) {
			if (value == null) this.removeAttribute("for");
			else this.setAttribute("for", String(value));
		}
		get disabled() {
			return this.hasAttribute("disabled");
		}
		set disabled(value) {
			this.toggleAttribute("disabled", Boolean(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		focus(options) {
			this._button.focus(options);
		}
		click() {
			this._button.click();
		}
		async copy() {
			if (this.disabled) return false;
			const target = this.target ?? (this.htmlFor ? this.ownerDocument?.getElementById(this.htmlFor) : null);
			const text = String(target ? target.value ?? target.textContent ?? "" : this.text);
			try {
				if (!globalThis.navigator?.clipboard?.writeText) throw new Error("Clipboard API is unavailable");
				await navigator.clipboard.writeText(text);
				this._status.textContent = "Copied";
				this._button.textContent = "Copied";
				clearTimeout(this._resetTimer);
				this._resetTimer = setTimeout(() => this._render(), 1800);
				const event = new Event("copy", {
					bubbles: true,
					composed: true
				});
				event.detail = { text };
				this.dispatchEvent(event);
				return true;
			} catch (error) {
				this._status.textContent = "Copy failed";
				const event = new Event("copyerror", {
					bubbles: true,
					composed: true
				});
				event.detail = { error };
				this.dispatchEvent(event);
				return false;
			}
		}
		_render() {
			this._button.textContent = this.label;
			this._button.disabled = this.disabled;
			this._button.setAttribute("aria-label", this.label);
		}
	};
	var BiscuitSuggestionView = class extends HTMLElementBase {
		constructor() {
			super();
			this._items = [];
			this._activeIndex = -1;
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = suggestionViewStyles;
			this._list = document.createElement("div");
			this._list.setAttribute("part", "list");
			this._list.setAttribute("role", "listbox");
			this._list.setAttribute("aria-label", "Suggestions");
			root.append(style, this._list);
			this.addEventListener("keydown", (event) => this._onKeyDown(event));
		}
		get items() {
			return this._items.slice();
		}
		set items(value) {
			if (!Array.isArray(value)) throw new TypeError("SuggestionsView items must be an array");
			this._items = value.map((item) => typeof item === "string" ? { label: item } : { ...item });
			this._activeIndex = this._items.length ? 0 : -1;
			this._render();
		}
		get activeIndex() {
			return this._activeIndex;
		}
		set activeIndex(value) {
			const index = Number(value);
			if (!Number.isInteger(index) || index < -1 || index >= this._items.length) throw new RangeError("Invalid suggestion index");
			this._activeIndex = index;
			this._render();
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		focus(options) {
			this._list.children[this._activeIndex]?.focus(options);
		}
		choose(index = this._activeIndex) {
			const item = this._items[index];
			if (!item) return null;
			this._activeIndex = index;
			this._render();
			const event = new Event("select", {
				bubbles: true,
				composed: true
			});
			event.detail = {
				item,
				index
			};
			this.dispatchEvent(event);
			return item;
		}
		_onKeyDown(event) {
			if (!this._items.length) return;
			let next = this._activeIndex;
			if (event.key === "ArrowDown") next = (next + 1) % this._items.length;
			else if (event.key === "ArrowUp") next = (next - 1 + this._items.length) % this._items.length;
			else if (event.key === "Home") next = 0;
			else if (event.key === "End") next = this._items.length - 1;
			else if (event.key === "Enter") {
				event.preventDefault();
				this.choose();
				return;
			} else return;
			event.preventDefault();
			this._activeIndex = next;
			this._render();
			this.focus();
		}
		_render() {
			const nodes = this._items.map((item, index) => {
				const option = document.createElement("button");
				option.type = "button";
				option.setAttribute("part", "option");
				option.setAttribute("role", "option");
				option.setAttribute("aria-selected", String(index === this._activeIndex));
				option.tabIndex = index === this._activeIndex ? 0 : -1;
				if (item.kind) {
					const kind = document.createElement("span");
					kind.setAttribute("part", "kind");
					kind.textContent = String(item.kind);
					option.append(kind);
				}
				const label = document.createElement("span");
				label.setAttribute("part", "label");
				label.textContent = String(item.label ?? "");
				option.append(label);
				if (item.detail) {
					const detail = document.createElement("span");
					detail.setAttribute("part", "detail");
					detail.textContent = String(item.detail);
					option.append(detail);
				}
				option.addEventListener("mouseenter", () => {
					this._activeIndex = index;
					this._updateSelection();
				});
				option.addEventListener("click", () => this.choose(index));
				return option;
			});
			if (!nodes.length) {
				const empty = document.createElement("div");
				empty.setAttribute("part", "empty");
				empty.textContent = "No suggestions";
				nodes.push(empty);
			}
			this._list.replaceChildren(...nodes);
		}
		_updateSelection() {
			Array.from(this._list.children).forEach((option, index) => {
				option.setAttribute("aria-selected", String(index === this._activeIndex));
				option.tabIndex = index === this._activeIndex ? 0 : -1;
			});
		}
	};
	var BiscuitCodeExampleView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["open", "label"];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = codeExampleViewStyles;
			const controls = document.createElement("div");
			controls.setAttribute("part", "controls");
			this._toggle = document.createElement("button");
			this._toggle.type = "button";
			this._toggle.setAttribute("part", "toggle");
			this._panel = document.createElement("div");
			this._panel.setAttribute("part", "panel");
			this._panel.id = "biscuit-code-example-" + ++codeExampleViewId;
			this._toggle.setAttribute("aria-controls", this._panel.id);
			this._panel.append(document.createElement("slot"));
			this._toggle.addEventListener("click", () => {
				this.toggle();
				this.dispatchEvent(new Event("change", {
					bubbles: true,
					composed: true
				}));
			});
			controls.append(this._toggle);
			root.append(style, controls, this._panel);
			this._renderState();
		}
		attributeChangedCallback(name, oldValue, newValue) {
			if (oldValue !== newValue) this._renderState();
		}
		get open() {
			return this.hasAttribute("open");
		}
		set open(value) {
			this.toggleAttribute("open", Boolean(value));
		}
		get label() {
			return this.getAttribute("label") ?? "";
		}
		set label(value) {
			if (value == null || value === "") this.removeAttribute("label");
			else this.setAttribute("label", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		show() {
			this.open = true;
		}
		hide() {
			this.open = false;
		}
		toggle() {
			this.toggleAttribute("open", !this.hasAttribute("open"));
		}
		_renderState() {
			const expanded = this.hasAttribute("open");
			const action = expanded ? "Hide code" : "Show code";
			this._toggle.textContent = action;
			this._toggle.setAttribute("aria-expanded", String(expanded));
			if (this.label) this._toggle.setAttribute("aria-label", action + " for " + this.label);
			else this._toggle.removeAttribute("aria-label");
			this._panel.hidden = !expanded;
		}
	};
	var BiscuitCodeView = class extends HTMLElementBase {
		static formAssociated = true;
		static get observedAttributes() {
			return [
				"value",
				"language",
				"readonly",
				"disabled",
				"line-numbers",
				"line-numbers-container-position",
				"line-number-position",
				"wrap",
				"tab-size",
				"copyable",
				"name",
				"resizable",
				"limit-resize-to-parent",
				"smart-pairs",
				"experimental-completion",
				"warn-large-files",
				"aria-label"
			];
		}
		constructor() {
			super();
			this._internals = this.attachInternals?.() ?? null;
			this._value = "";
			this._dirty = false;
			this._lines = [""];
			this._lineStarts = [0];
			this._states = /* @__PURE__ */ new Map();
			this._cache = /* @__PURE__ */ new Map();
			this._generation = 0;
			this._hydrating = false;
			this._hydrationTarget = 0;
			this._offsets = [0, 22];
			this._offsetMetrics = null;
			this._changedLineRange = null;
			this._renderQueued = false;
			this._detectTimer = null;
			this._lastFocusValue = "";
			this._resolvedLanguage = "plain";
			this._resetListener = () => this.formResetCallback();
			this._resizeMaxWidth = null;
			this._resizeMaxHeight = null;
			this._completionProvider = null;
			this._completionRange = null;
			this._applyingCompletion = false;
			this._largeFileMode = false;
			this._largeFileLimitIgnored = false;
			this._largeFileWarningOpen = false;
			this._largeFilePrevious = null;
			this._onWindowResize = () => {
				this._updateResizeLimit(true);
				this._positionCompletion();
			};
			this.addEventListener("pointerdown", () => this._updateResizeLimit(false));
			const root = this.attachShadow({ mode: "open" });
			const style = document.createElement("style");
			style.textContent = codeViewStyles;
			const frame = document.createElement("div");
			frame.setAttribute("part", "frame");
			this._toolbar = document.createElement("div");
			this._toolbar.setAttribute("part", "toolbar");
			this._toolbarStart = document.createElement("slot");
			this._toolbarStart.name = "toolbar-start";
			this._toolbarStart.setAttribute("part", "toolbar-start");
			this._toolbarStart.addEventListener("slotchange", () => this._syncToolbar());
			this._copyButton = document.createElement(COPYBUTTONVIEW_TAG_NAME);
			this._copyButton.target = this;
			this._toolbar.append(this._toolbarStart, this._copyButton);
			this._workspace = document.createElement("div");
			this._workspace.setAttribute("part", "workspace");
			this._highlight = document.createElement("div");
			this._highlight.setAttribute("part", "highlight");
			this._linesLayer = document.createElement("div");
			this._linesLayer.setAttribute("part", "lines");
			this._lineProbe = document.createElement("span");
			this._lineProbe.setAttribute("part", "line-probe");
			this._lineProbe.setAttribute("aria-hidden", "true");
			this._lineProbe.textContent = "M";
			this._highlight.append(this._linesLayer, this._lineProbe);
			this._gutter = document.createElement("div");
			this._gutter.setAttribute("part", "gutter");
			this._numbers = document.createElement("div");
			this._numbers.setAttribute("part", "numbers");
			this._gutter.append(this._numbers);
			this._editor = document.createElement("textarea");
			this._editor.setAttribute("part", "editor");
			this._editor.setAttribute("aria-label", "Code editor");
			this._editor.spellcheck = false;
			this._editor.autocapitalize = "off";
			this._editor.autocomplete = "off";
			this._editor.addEventListener("input", () => this._onInput());
			this._editor.addEventListener("scroll", () => {
				this._queueRender();
				this._positionCompletion();
			});
			this._editor.addEventListener("keydown", (event) => this._onKeyDown(event));
			this._editor.addEventListener("focus", () => {
				this._lastFocusValue = this.value;
				this._updateCompletion();
			});
			this._editor.addEventListener("click", () => this._updateCompletion());
			this._editor.addEventListener("keyup", (event) => {
				if ([
					"ArrowLeft",
					"ArrowRight",
					"Home",
					"End",
					"PageUp",
					"PageDown"
				].includes(event.key)) this._updateCompletion();
			});
			this._editor.addEventListener("blur", () => {
				this._hideCompletion();
				if (this.value !== this._lastFocusValue) this.dispatchEvent(new Event("change", {
					bubbles: true,
					composed: true
				}));
			});
			this._completionMenu = document.createElement(SUGGESTIONVIEW_TAG_NAME);
			this._completionMenu.setAttribute("part", "suggestions");
			this._completionMenu.id = `biscuit-code-suggestions-${++codeCompletionId}`;
			this._completionMenu.hidden = true;
			this._completionMenu.addEventListener("pointerdown", (event) => event.preventDefault());
			this._completionMenu.addEventListener("select", (event) => this._acceptCompletion(event.detail.item));
			this._largeFileStatus = document.createElement("div");
			this._largeFileStatus.setAttribute("part", "large-file-status");
			this._largeFileStatus.setAttribute("role", "status");
			this._largeFileStatus.hidden = true;
			this._largeFileWarning = document.createElement("div");
			this._largeFileWarning.setAttribute("part", "large-file-warning");
			this._largeFileWarning.hidden = true;
			const warningDialog = document.createElement("div");
			warningDialog.setAttribute("part", "large-file-dialog");
			warningDialog.setAttribute("role", "alertdialog");
			warningDialog.setAttribute("aria-modal", "true");
			const warningId = ++codeLargeWarningId;
			const warningTitle = document.createElement("h3");
			warningTitle.id = `biscuit-code-large-title-${warningId}`;
			warningTitle.setAttribute("part", "large-file-title");
			warningTitle.textContent = "Large code file";
			this._largeFileMessage = document.createElement("p");
			this._largeFileMessage.id = `biscuit-code-large-message-${warningId}`;
			this._largeFileMessage.setAttribute("part", "large-file-message");
			warningDialog.setAttribute("aria-labelledby", warningTitle.id);
			warningDialog.setAttribute("aria-describedby", this._largeFileMessage.id);
			const warningActions = document.createElement("div");
			warningActions.setAttribute("part", "large-file-actions");
			const makeWarningButton = (label, action) => {
				const button = document.createElement("button");
				button.type = "button";
				button.textContent = label;
				button.addEventListener("click", () => this._resolveLargeFileWarning(action));
				warningActions.append(button);
				return button;
			};
			this._largeFileOk = makeWarningButton("OK", "ok");
			this._largeFileCancel = makeWarningButton("Cancel", "cancel");
			this._largeFileIgnore = makeWarningButton("Ignore", "ignore");
			this._largeFileWarning.addEventListener("keydown", (event) => {
				if (event.key === "Escape") {
					event.preventDefault();
					this._resolveLargeFileWarning("cancel");
				}
				if (event.key !== "Tab") return;
				const buttons = Array.from(warningActions.children);
				if (buttons.indexOf(this.shadowRoot.activeElement) === (event.shiftKey ? 0 : buttons.length - 1)) {
					event.preventDefault();
					buttons[event.shiftKey ? buttons.length - 1 : 0].focus();
				}
			});
			warningDialog.append(warningTitle, this._largeFileMessage, warningActions);
			this._largeFileWarning.append(warningDialog);
			this._workspace.append(this._highlight, this._gutter, this._editor);
			frame.append(this._toolbar, this._largeFileStatus, this._workspace, this._completionMenu, this._largeFileWarning);
			root.append(style, frame);
			this._syncAttributes();
			this._languageListener = () => this._languageChanged();
		}
		connectedCallback() {
			codeLanguageListeners.add(this._languageListener);
			if (!this._internals && !this._fallback) {
				this._fallback = document.createElement("input");
				this._fallback.type = "hidden";
				this.append(this._fallback);
			}
			if (this._fallback) {
				this._form = this.closest?.("form") ?? (this.getAttribute("form") ? this.ownerDocument?.getElementById(this.getAttribute("form")) : null);
				this._form?.addEventListener("reset", this._resetListener);
			}
			if (!this._dirty) this._setValue(this.defaultValue, false, false, true);
			this._syncAttributes();
			this._languageChanged();
			if (globalThis.ResizeObserver) {
				this._observer = new ResizeObserver(() => this._queueRender(true));
				this._observer.observe(this);
				if (this.parentElement) {
					this._parentObserver = new ResizeObserver(() => this._updateResizeLimit(false));
					this._parentObserver.observe(this.parentElement);
				}
			}
			this._updateResizeLimit(true);
			globalThis.addEventListener?.("resize", this._onWindowResize);
			this._queueRender(true);
			if (this._largeFileWarningOpen) this._largeFileOk.focus();
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._parentObserver?.disconnect();
			clearTimeout(this._detectTimer);
			this._generation++;
			this._hydrating = false;
			this._hydrationTarget = 0;
			codeLanguageListeners.delete(this._languageListener);
			this._form?.removeEventListener("reset", this._resetListener);
			globalThis.removeEventListener?.("resize", this._onWindowResize);
		}
		attributeChangedCallback(name) {
			if (name === "value" && !this._dirty) {
				this._setValue(this.defaultValue, false, false, true);
				this._hideCompletion();
			} else if (name === "language") this._languageChanged();
			else if (name === "warn-large-files") {
				if (!this.warnLargeFiles && this.lineCount >= CODEVIEW_LARGE_FILE_LINE_LIMIT) {
					if (this._largeFileWarningOpen) this._closeLargeFileWarning(false);
					this._largeFileLimitIgnored = true;
					this._largeFileMode = false;
					this.removeAttribute("data-large-file-mode");
					this._cache.clear();
					this._states.clear();
					this._states.set(0, codeLanguages.get(this._resolvedLanguage)?.initialState ?? "");
					this._generation++;
				}
				this._syncAttributes();
				this._updateLargeFileStatus();
				this._queueRender(true);
			} else {
				this._syncAttributes();
				if (name === "resizable" || name === "limit-resize-to-parent") this._updateResizeLimit(true);
				if (name === "experimental-completion" && this.experimentalCompletion) this._updateCompletion();
				this._queueRender(true);
			}
		}
		formResetCallback() {
			this._dirty = false;
			this._setValue(this.defaultValue, false, false, true);
		}
		formDisabledCallback(disabled) {
			this._editor.disabled = disabled;
			if (disabled) this._hideCompletion();
			this._syncFormValue();
		}
		get value() {
			return this._value;
		}
		set value(value) {
			this._setValue(String(value ?? ""), true, false, true);
			this._hideCompletion();
		}
		get lineCount() {
			return this._lines.length;
		}
		get largeFileMode() {
			return this._largeFileMode;
		}
		get largeFileLimitIgnored() {
			return this._largeFileLimitIgnored;
		}
		get warnLargeFiles() {
			return this.getAttribute("warn-large-files") !== "false";
		}
		set warnLargeFiles(value) {
			if (value) this.removeAttribute("warn-large-files");
			else this.setAttribute("warn-large-files", "false");
		}
		get defaultValue() {
			return this.getAttribute("value") ?? "";
		}
		set defaultValue(value) {
			if (value == null) this.removeAttribute("value");
			else this.setAttribute("value", String(value));
		}
		get language() {
			return this.getAttribute("language") ?? "plain";
		}
		set language(value) {
			if (value == null) this.removeAttribute("language");
			else this.setAttribute("language", String(value));
		}
		get resolvedLanguage() {
			return this._resolvedLanguage;
		}
		get editable() {
			return !this.hasAttribute("readonly");
		}
		set editable(value) {
			this.toggleAttribute("readonly", !Boolean(value));
		}
		get readOnly() {
			return !this.editable;
		}
		set readOnly(value) {
			this.editable = !value;
		}
		get disabled() {
			return this.hasAttribute("disabled");
		}
		set disabled(value) {
			this.toggleAttribute("disabled", Boolean(value));
		}
		get lineNumbers() {
			return this.getAttribute("line-numbers") !== "false";
		}
		set lineNumbers(value) {
			this.setAttribute("line-numbers", String(Boolean(value)));
		}
		get lineNumbersContainerPosition() {
			return this.getAttribute("line-numbers-container-position") === "right" ? "right" : "left";
		}
		set lineNumbersContainerPosition(value) {
			if (value !== "left" && value !== "right") throw new TypeError("lineNumbersContainerPosition must be \"left\" or \"right\"");
			if (value === "left") this.removeAttribute("line-numbers-container-position");
			else this.setAttribute("line-numbers-container-position", value);
		}
		get lineNumberPosition() {
			return this.getAttribute("line-number-position") === "left" ? "left" : "right";
		}
		set lineNumberPosition(value) {
			if (value !== "left" && value !== "right") throw new TypeError("lineNumberPosition must be \"left\" or \"right\"");
			if (value === "right") this.removeAttribute("line-number-position");
			else this.setAttribute("line-number-position", value);
		}
		get wrap() {
			return this.hasAttribute("wrap");
		}
		set wrap(value) {
			this.toggleAttribute("wrap", Boolean(value));
		}
		get resizable() {
			return this.hasAttribute("resizable");
		}
		set resizable(value) {
			this.toggleAttribute("resizable", Boolean(value));
		}
		get limitResizeToParent() {
			return this.hasAttribute("limit-resize-to-parent");
		}
		set limitResizeToParent(value) {
			this.toggleAttribute("limit-resize-to-parent", Boolean(value));
		}
		get smartPairs() {
			return this.hasAttribute("smart-pairs");
		}
		set smartPairs(value) {
			this.toggleAttribute("smart-pairs", Boolean(value));
		}
		get experimentalCompletion() {
			return this.hasAttribute("experimental-completion");
		}
		set experimentalCompletion(value) {
			this.toggleAttribute("experimental-completion", Boolean(value));
		}
		get completionProvider() {
			return this._completionProvider;
		}
		set completionProvider(value) {
			if (value != null && typeof value !== "function") throw new TypeError("completionProvider must be a function or null");
			this._completionProvider = value;
			this._updateCompletion();
		}
		get copyable() {
			return this.hasAttribute("copyable");
		}
		set copyable(value) {
			this.toggleAttribute("copyable", Boolean(value));
		}
		get tabSize() {
			return Number(this.getAttribute("tab-size")) || 2;
		}
		set tabSize(value) {
			if (!Number.isInteger(Number(value)) || Number(value) < 1 || Number(value) > 16) throw new RangeError("tabSize must be 1–16");
			this.setAttribute("tab-size", String(value));
		}
		get name() {
			return this.getAttribute("name") ?? "";
		}
		set name(value) {
			if (value == null) this.removeAttribute("name");
			else this.setAttribute("name", String(value));
		}
		get theme() {
			return this.getAttribute("theme") ?? "inherit";
		}
		set theme(value) {
			if (value == null || value === "inherit") this.removeAttribute("theme");
			else this.setAttribute("theme", String(value));
		}
		get selectionStart() {
			return this._editor.selectionStart ?? 0;
		}
		get selectionEnd() {
			return this._editor.selectionEnd ?? 0;
		}
		setSelectionRange(start, end, direction) {
			this._editor.setSelectionRange?.(start, end, direction);
		}
		focus(options) {
			this._editor.focus(options);
		}
		getCaretContext() {
			const offset = this.selectionStart;
			let low = 0;
			let high = this._lineStarts.length;
			while (low + 1 < high) {
				const middle = low + high >> 1;
				if (this._lineStarts[middle] <= offset) low = middle;
				else high = middle;
			}
			const line = low + 1;
			const column = offset - this._lineStarts[low] + 1;
			const currentLine = this.value.slice(this._lineStarts[low], offset);
			const prefix = /[\w$-]*$/.exec(currentLine)?.[0] ?? "";
			return {
				value: this.value,
				language: this.resolvedLanguage,
				offset,
				line,
				column,
				prefix
			};
		}
		replaceRange(start, end, text) {
			const from = Math.max(0, Math.min(this.value.length, Number(start)));
			const to = Math.max(from, Math.min(this.value.length, Number(end)));
			const insert = String(text ?? "");
			this._editor.setRangeText?.(insert, from, to, "end");
			this._setValue(this.value.slice(0, from) + insert + this.value.slice(to), true, true);
			this.setSelectionRange(from + insert.length, from + insert.length);
			this._updateCompletion();
		}
		_setValue(value, dirty, notify, newDocument = false) {
			const previous = this._value;
			this._value = value.replace(/\r\n?/g, "\n");
			if (dirty) this._dirty = true;
			if (this._editor.value !== this._value) this._editor.value = this._value;
			this._syncFormValue();
			if (previous !== this._value) {
				this._generation++;
				this._hydrating = false;
				this._hydrationTarget = 0;
				const oldLines = this._lines;
				const previousIgnored = this._largeFileLimitIgnored;
				this._lines = this._value.split("\n");
				this._lineStarts = new Array(this._lines.length);
				let lineStart = 0;
				for (let index = 0; index < this._lines.length; index++) {
					this._lineStarts[index] = lineStart;
					lineStart += this._lines[index].length + 1;
				}
				const isLarge = this._lines.length >= CODEVIEW_LARGE_FILE_LINE_LIMIT;
				const shouldWarn = this.warnLargeFiles && isLarge && (oldLines.length < CODEVIEW_LARGE_FILE_LINE_LIMIT || newDocument);
				if (!isLarge || newDocument) this._largeFileLimitIgnored = isLarge && !this.warnLargeFiles;
				if (isLarge && !this.warnLargeFiles) this._largeFileLimitIgnored = true;
				this._largeFileMode = isLarge && !this._largeFileLimitIgnored;
				this.toggleAttribute("data-large-file-mode", this._largeFileMode);
				this._syncAttributes();
				this._updateLargeFileStatus();
				if (this._largeFileMode) {
					this._cache.clear();
					this._states.clear();
					this._offsets = [];
					this._changedLineRange = null;
				} else {
					let first = 0;
					while (first < oldLines.length && first < this._lines.length && oldLines[first] === this._lines[first]) first++;
					let oldEnd = oldLines.length;
					let newEnd = this._lines.length;
					while (oldEnd > first && newEnd > first && oldLines[oldEnd - 1] === this._lines[newEnd - 1]) {
						oldEnd--;
						newEnd--;
					}
					if (this._changedLineRange) this._remeasure = true;
					this._changedLineRange = {
						first,
						oldEnd,
						newEnd,
						oldLength: oldLines.length
					};
					for (const key of this._cache.keys()) if (key >= first) this._cache.delete(key);
					for (const key of this._states.keys()) if (key >= first) this._states.delete(key);
				}
				this._states.set(0, codeLanguages.get(this._resolvedLanguage)?.initialState ?? "");
				if (this.language === "auto") {
					clearTimeout(this._detectTimer);
					this._detectTimer = setTimeout(() => this._languageChanged(), 300);
				}
				this._queueRender();
				if (shouldWarn) this._showLargeFileWarning(previous, previousIgnored);
				else if (!isLarge && this._largeFileWarningOpen) this._closeLargeFileWarning(false);
				if (notify) this.dispatchEvent(new Event("input", {
					bubbles: true,
					composed: true
				}));
			}
		}
		_onInput() {
			this._setValue(this._editor.value, true, true);
			this._updateCompletion();
		}
		_updateLargeFileStatus() {
			const large = this._lines.length >= CODEVIEW_LARGE_FILE_LINE_LIMIT;
			this._largeFileStatus.hidden = !large;
			this._largeFileStatus.textContent = large ? `${this._lines.length.toLocaleString()} lines · ${this._largeFileMode ? "highlighting and line wrap off" : `${this.warnLargeFiles ? "limit ignored" : "warning disabled"}; highlighting and line wrap available`}` : "";
		}
		_showLargeFileWarning(previous, previousIgnored) {
			this._largeFilePrevious = {
				value: previous,
				ignored: previousIgnored
			};
			this._largeFileWarningOpen = true;
			const limitState = this._lines.length === CODEVIEW_LARGE_FILE_LINE_LIMIT ? "reached" : "exceeded";
			this._largeFileMessage.textContent = `This file has ${this._lines.length.toLocaleString()} lines. You've ${limitState} the ${CODEVIEW_LARGE_FILE_LINE_LIMIT.toLocaleString()}-line limit. OK keeps this file with highlighting and line wrap off. Cancel restores the previous text. Ignore keeps this file with highlighting and line wrap available.`;
			this._largeFileWarning.hidden = false;
			this._workspace.inert = true;
			this._hideCompletion();
			if (this.isConnected) this._largeFileOk.focus();
		}
		_closeLargeFileWarning(focusEditor = true) {
			this._largeFileWarningOpen = false;
			this._largeFilePrevious = null;
			this._largeFileWarning.hidden = true;
			this._workspace.inert = false;
			if (focusEditor && this.isConnected) this.focus();
		}
		_resolveLargeFileWarning(action) {
			if (!this._largeFileWarningOpen) return;
			const previous = this._largeFilePrevious;
			this._largeFilePrevious = null;
			this._closeLargeFileWarning(false);
			if (action === "cancel") {
				this._largeFileLimitIgnored = previous.ignored;
				this._setValue(previous.value, true, true);
			} else if (action === "ignore") {
				this._largeFileLimitIgnored = true;
				this._largeFileMode = false;
				this.removeAttribute("data-large-file-mode");
				this._states.clear();
				this._states.set(0, codeLanguages.get(this._resolvedLanguage)?.initialState ?? "");
				this._cache.clear();
				this._generation++;
				this._hydrating = false;
				this._hydrationTarget = 0;
				this._syncAttributes();
				this._updateLargeFileStatus();
				this._queueRender(true);
			}
			const event = new Event("largefiledecision", {
				bubbles: true,
				composed: true
			});
			event.detail = {
				action,
				lineCount: this.lineCount,
				largeFileMode: this.largeFileMode
			};
			this.dispatchEvent(event);
			if (this.isConnected) this.focus();
		}
		_syncAttributes() {
			this._editor.readOnly = !this.editable;
			this._editor.disabled = this.disabled;
			this._editor.setAttribute("aria-label", this.getAttribute("aria-label") ?? "Code editor");
			if (this.experimentalCompletion) {
				this._editor.setAttribute("aria-autocomplete", "list");
				this._editor.setAttribute("aria-controls", this._completionMenu.id);
				if (!this.editable || this.disabled) this._hideCompletion();
			} else {
				this._editor.removeAttribute("aria-autocomplete");
				this._editor.removeAttribute("aria-controls");
				this._hideCompletion();
			}
			this._editor.wrap = this.wrap && !this._largeFileMode ? "soft" : "off";
			this._editor.style.setProperty("--_code-tab-size", String(this.tabSize));
			this._linesLayer.style.setProperty("--_code-tab-size", String(this.tabSize));
			this._syncToolbar();
			this._syncFormValue();
		}
		_syncToolbar() {
			const slotted = this._toolbarStart.assignedElements?.() ?? Array.from(this.children ?? []).filter((child) => child.getAttribute?.("slot") === "toolbar-start");
			this._copyButton.hidden = !this.copyable;
			this._toolbar.hidden = !this.copyable && !slotted.length;
		}
		_syncFormValue() {
			this._internals?.setFormValue(this.disabled ? null : this._value);
			if (this._fallback) {
				this._fallback.name = this.name;
				this._fallback.value = this._value;
				this._fallback.disabled = this.disabled || !this.name;
				if (this.hasAttribute("form")) this._fallback.setAttribute("form", this.getAttribute("form"));
				else this._fallback.removeAttribute("form");
			}
		}
		_updateResizeLimit(allowGrowth) {
			if (!this.resizable || !this.limitResizeToParent) {
				this._resizeMaxWidth = null;
				this._resizeMaxHeight = null;
				this.style.removeProperty("--biscuit-codeview-parent-max-width");
				this.style.removeProperty("--biscuit-codeview-parent-max-height");
				return;
			}
			if (!this.isConnected || !this.parentElement?.getBoundingClientRect || !this.getBoundingClientRect) return;
			const parent = this.parentElement.getBoundingClientRect();
			const view = this.getBoundingClientRect();
			if (!parent.width || !parent.height) return;
			const width = Math.max(0, parent.right - view.left);
			const height = Math.max(0, parent.bottom - view.top);
			this._resizeMaxWidth = allowGrowth || this._resizeMaxWidth == null ? width : Math.min(this._resizeMaxWidth, width);
			this._resizeMaxHeight = allowGrowth || this._resizeMaxHeight == null ? height : Math.min(this._resizeMaxHeight, height);
			this.style.setProperty("--biscuit-codeview-parent-max-width", `${this._resizeMaxWidth}px`);
			this.style.setProperty("--biscuit-codeview-parent-max-height", `${this._resizeMaxHeight}px`);
		}
		_languageChanged() {
			const next = this.language === "auto" ? detectCodeLanguage(this.value) : resolveCodeLanguage(this.language);
			if (next !== this._resolvedLanguage) {
				this._generation++;
				this._hydrating = false;
				this._hydrationTarget = 0;
				this._resolvedLanguage = next;
				this._states.clear();
				this._cache.clear();
				this._states.set(0, codeLanguages.get(next)?.initialState ?? "");
				this.dispatchEvent(new Event("languagechange", {
					bubbles: true,
					composed: true
				}));
			}
			this._queueRender();
			this._hideCompletion();
		}
		_onKeyDown(event) {
			if (event.isComposing || !this.editable || this.disabled || event.altKey || event.ctrlKey || event.metaKey) return;
			if (!this._completionMenu.hidden) {
				if (event.key === "Escape") {
					event.preventDefault();
					this._hideCompletion();
					return;
				}
				if (event.key === "ArrowDown" || event.key === "ArrowUp") {
					event.preventDefault();
					const delta = event.key === "ArrowDown" ? 1 : -1;
					const count = this._completionMenu.items.length;
					this._completionMenu.activeIndex = (this._completionMenu.activeIndex + delta + count) % count;
					this._completionMenu.shadowRoot?.querySelectorAll?.("[part=\"option\"]")?.[this._completionMenu.activeIndex]?.scrollIntoView?.({ block: "nearest" });
					return;
				}
				if (event.key === "Enter" || event.key === "Tab" && !event.shiftKey) {
					event.preventDefault();
					this._completionMenu.choose();
					return;
				}
			}
			const start = this.selectionStart;
			const end = this.selectionEnd;
			const before = this.value.slice(0, start);
			if ((this.smartPairs || this.experimentalCompletion) && start === end) {
				if (event.key === "Backspace" && "([{\"'`".includes(this.value[start - 1]) && {
					"(": ")",
					"[": "]",
					"{": "}",
					"\"": "\"",
					"'": "'",
					"`": "`"
				}[this.value[start - 1]] === this.value[start]) {
					event.preventDefault();
					this.replaceRange(start - 1, start + 1, "");
					return;
				}
				if (")]}'\"`".includes(event.key) && this.value[start] === event.key) {
					event.preventDefault();
					this.setSelectionRange(start + 1, start + 1);
					this._updateCompletion();
					return;
				}
			}
			if (event.key === "Tab") {
				event.preventDefault();
				const lineStart = before.lastIndexOf("\n") + 1;
				if (start !== end && this.value.slice(start, end).includes("\n")) {
					const block = this.value.slice(lineStart, end);
					const lines = block.split("\n");
					if (event.shiftKey) {
						const trimmed = lines.map((line) => line.replace(new RegExp("^ {1," + this.tabSize + "}"), ""));
						const firstRemoved = lines[0].length - trimmed[0].length;
						const totalRemoved = block.length - trimmed.join("\n").length;
						this.replaceRange(lineStart, end, trimmed.join("\n"));
						this.setSelectionRange(Math.max(lineStart, start - firstRemoved), end - totalRemoved);
					} else {
						const indent = " ".repeat(this.tabSize);
						this.replaceRange(lineStart, end, lines.map((line) => indent + line).join("\n"));
						this.setSelectionRange(start + this.tabSize, end + this.tabSize * lines.length);
					}
				} else if (event.shiftKey) {
					const match = new RegExp("^ {1," + this.tabSize + "}").exec(this.value.slice(lineStart));
					if (match) {
						this.replaceRange(lineStart, lineStart + match[0].length, "");
						this.setSelectionRange(Math.max(lineStart, start - match[0].length), Math.max(lineStart, end - match[0].length));
					}
				} else this.replaceRange(start, end, " ".repeat(this.tabSize));
				return;
			}
			if (event.key === "Enter") {
				const indent = /^[ \t]*/.exec(this.value.slice(before.lastIndexOf("\n") + 1))?.[0] ?? "";
				if (indent) {
					event.preventDefault();
					this.replaceRange(start, end, "\n" + indent);
				}
				return;
			}
			if (this.smartPairs || this.experimentalCompletion) {
				const pair = {
					"(": ")",
					"[": "]",
					"{": "}",
					"\"": "\"",
					"'": "'",
					"`": "`"
				}[event.key];
				if (pair) {
					event.preventDefault();
					this.replaceRange(start, end, event.key + this.value.slice(start, end) + pair);
					this.setSelectionRange(start + 1, start + 1);
					if (start !== end) this.setSelectionRange(start + 1, start + 1 + end - start);
					this._updateCompletion();
				}
			}
		}
		showCompletions() {
			this._updateCompletion();
			return !this._completionMenu.hidden;
		}
		_hideCompletion() {
			this._completionMenu.hidden = true;
			this._completionRange = null;
			this._editor.setAttribute("aria-expanded", "false");
		}
		_updateCompletion() {
			if (this._largeFileWarningOpen) {
				this._hideCompletion();
				return;
			}
			if (this._applyingCompletion) return;
			const providers = [this._completionProvider, ...codeCompletionProviders.get(this.resolvedLanguage) ?? []].filter(Boolean);
			if (!this.experimentalCompletion || !this.editable || this.disabled || !providers.length || this.selectionStart !== this.selectionEnd) {
				this._hideCompletion();
				return;
			}
			const context = this.getCaretContext();
			const items = [];
			const seen = /* @__PURE__ */ new Set();
			for (const provider of providers) {
				let results;
				try {
					results = provider(context, this);
				} catch (error) {
					const event = new Event("completionerror", {
						bubbles: true,
						composed: true
					});
					event.detail = {
						error,
						provider
					};
					this.dispatchEvent(event);
					continue;
				}
				if (!Array.isArray(results)) continue;
				for (const result of results) {
					const item = typeof result === "string" ? { label: result } : result;
					if (!item || typeof item.label !== "string" || seen.has(item.label)) continue;
					seen.add(item.label);
					items.push(item);
				}
			}
			if (!items.length) {
				this._hideCompletion();
				return;
			}
			const match = /[A-Za-z_$][\w$]*$/.exec(context.prefix);
			this._completionRange = {
				start: context.offset - (match?.[0].length ?? 0),
				end: context.offset
			};
			this._completionMenu.items = items;
			this._completionMenu.hidden = false;
			this._editor.setAttribute("aria-expanded", "true");
			this._positionCompletion();
		}
		_positionCompletion() {
			if (this._completionMenu.hidden || !this._editor.getBoundingClientRect) return;
			const context = this.getCaretContext();
			const style = globalThis.getComputedStyle?.(this._editor);
			const editor = this._editor.getBoundingClientRect();
			const frame = this._workspace.parentElement?.getBoundingClientRect?.();
			if (!frame) return;
			const fontSize = Number.parseFloat(style?.fontSize) || 14;
			const lineHeight = Number.parseFloat(style?.lineHeight) || fontSize * 1.55;
			const currentLine = context.value.slice(this._lineStarts[context.line - 1], context.offset).replace(/\t/g, " ".repeat(this.tabSize));
			const measure = (this._measureCanvas ??= document.createElement("canvas")).getContext?.("2d");
			if (measure) measure.font = style?.font ?? `${fontSize}px monospace`;
			const width = measure?.measureText(currentLine).width ?? currentLine.length * fontSize * .61;
			const x = editor.left - frame.left + (Number.parseFloat(style?.paddingLeft) || 0) + width - this._editor.scrollLeft;
			const y = editor.top - frame.top + (Number.parseFloat(style?.paddingTop) || 0) + context.line * lineHeight - this._editor.scrollTop + 2;
			const menuWidth = this._completionMenu.getBoundingClientRect?.().width || 280;
			this._completionMenu.style.left = `${Math.max(4, Math.min(x, frame.width - menuWidth - 4))}px`;
			this._completionMenu.style.top = `${Math.max(0, y)}px`;
		}
		_acceptCompletion(item) {
			if (!this._completionRange) return;
			const start = Number.isInteger(item.replaceStart) ? item.replaceStart : this._completionRange.start;
			const end = Number.isInteger(item.replaceEnd) ? item.replaceEnd : this._completionRange.end;
			this._applyingCompletion = true;
			try {
				this.replaceRange(start, end, item.insertText ?? item.label);
				this.focus();
			} finally {
				this._applyingCompletion = false;
				this._hideCompletion();
			}
		}
		_queueRender(remeasure = false) {
			if (remeasure) this._remeasure = true;
			if (this._renderQueued) return;
			this._renderQueued = true;
			(globalThis.requestAnimationFrame ?? ((callback) => setTimeout(callback, 0)))(() => {
				this._renderQueued = false;
				this._renderVisible();
			});
		}
		_layoutMetrics() {
			const style = globalThis.getComputedStyle?.(this._editor);
			const fontSize = Number.parseFloat(style?.fontSize) || 14;
			let lineHeight = Number.parseFloat(style?.lineHeight) || fontSize * 1.55;
			const probe = this._lineProbe.getBoundingClientRect?.();
			const probeHeight = probe?.height;
			if (probeHeight > 0) lineHeight = probeHeight;
			else if (!this.wrap || this._largeFileMode) {
				const contentHeight = this._editor.scrollHeight - (Number.parseFloat(style?.paddingTop) || 0) - (Number.parseFloat(style?.paddingBottom) || 0);
				if (this._lines.length > 1 && this._editor.scrollHeight > this._editor.clientHeight + lineHeight) {
					const measured = contentHeight / this._lines.length;
					if (measured > lineHeight * .9 && measured < lineHeight * 1.1) lineHeight = measured;
				}
			}
			const width = this._editor.clientWidth || this.clientWidth || 600;
			const fallbackPadding = (this.lineNumbers ? 56 : 0) + 24;
			const paddingLeft = Number.parseFloat(style?.paddingLeft);
			const paddingRight = Number.parseFloat(style?.paddingRight);
			const padding = Number.isFinite(paddingLeft) && Number.isFinite(paddingRight) ? paddingLeft + paddingRight : fallbackPadding;
			const charWidth = probe?.width > 0 ? probe.width : fontSize * .61;
			const columns = Math.max(8, Math.floor((width - padding) / charWidth));
			return {
				lineHeight,
				columns
			};
		}
		_buildOffsets({ lineHeight, columns } = this._layoutMetrics()) {
			const metrics = {
				lineHeight,
				columns,
				tabSize: this.tabSize,
				wrap: this.wrap,
				largeFileMode: this._largeFileMode
			};
			const previous = this._offsetMetrics;
			const sameMetrics = previous && Object.keys(metrics).every((key) => metrics[key] === previous[key]);
			if (!this._remeasure && sameMetrics && this._offsets.length === this._lines.length + 1 && !this._changedLineRange) return;
			const changed = this._changedLineRange;
			if (!this._remeasure && sameMetrics && changed && this._offsets.length === changed.oldLength + 1) {
				const offsets = this._offsets.slice(0, changed.first + 1);
				for (let index = changed.first; index < changed.newEnd; index++) {
					const line = this._lines[index];
					const columnsUsed = line.includes("	") ? line.replace(/\t/g, " ".repeat(this.tabSize)).length : line.length;
					offsets.push(offsets.at(-1) + Math.max(1, Math.ceil((columnsUsed + 1) / columns)) * lineHeight);
				}
				const delta = offsets.at(-1) - this._offsets[changed.oldEnd];
				for (let index = changed.oldEnd + 1; index < this._offsets.length; index++) offsets.push(this._offsets[index] + delta);
				this._offsets = offsets;
				this._changedLineRange = null;
				return;
			}
			const offsets = [0];
			for (const line of this._lines) {
				const columnsUsed = line.includes("	") ? line.replace(/\t/g, " ".repeat(this.tabSize)).length : line.length;
				const rows = this.wrap && !this._largeFileMode ? Math.max(1, Math.ceil((columnsUsed + 1) / columns)) : 1;
				offsets.push(offsets[offsets.length - 1] + rows * lineHeight);
			}
			this._offsets = offsets;
			this._offsetMetrics = metrics;
			this._changedLineRange = null;
			this._remeasure = false;
		}
		_tokensFor(index) {
			if (this._largeFileMode) return this._lines[index] ? [{
				type: "text",
				text: this._lines[index]
			}] : [];
			if (this._cache.has(index)) return this._cache.get(index);
			let cursor = index;
			while (cursor > 0 && !this._states.has(cursor)) cursor--;
			if (index - cursor > 200) {
				this._hydrateStates(index);
				return this._lines[index] ? [{
					type: "text",
					text: this._lines[index]
				}] : [];
			}
			let state = this._states.get(cursor) ?? codeLanguages.get(this._resolvedLanguage)?.initialState ?? "";
			for (; cursor <= index; cursor++) {
				let result;
				try {
					result = scanCodeLine(this._lines[cursor], this._resolvedLanguage, state);
				} catch {
					result = {
						tokens: [{
							type: "text",
							text: this._lines[cursor]
						}],
						state: ""
					};
				}
				state = result.state;
				if (cursor === index) this._cache.set(index, result.tokens);
				if ((cursor + 1) % 100 === 0) this._states.set(cursor + 1, state);
			}
			return this._cache.get(index);
		}
		_hydrateStates(target) {
			if (this._largeFileMode) return;
			this._hydrationTarget = Math.max(this._hydrationTarget, target);
			if (this._hydrating) return;
			let cursor = 0;
			for (const key of this._states.keys()) if (key <= target && key > cursor) cursor = key;
			let state = this._states.get(cursor) ?? codeLanguages.get(this._resolvedLanguage)?.initialState ?? "";
			const generation = this._generation;
			this._hydrating = true;
			const step = () => {
				if (generation !== this._generation) return;
				const end = Math.min(this._hydrationTarget + 1, cursor + 200, this._lines.length);
				while (cursor < end) {
					try {
						state = scanCodeLine(this._lines[cursor], this._resolvedLanguage, state).state;
					} catch {
						state = "";
					}
					cursor++;
					if (cursor % 100 === 0) this._states.set(cursor, state);
				}
				if (cursor <= this._hydrationTarget && cursor < this._lines.length) setTimeout(step, 0);
				else {
					this._hydrating = false;
					this._queueRender();
				}
			};
			setTimeout(step, 0);
		}
		_renderVisible() {
			const metrics = this._layoutMetrics();
			const unwrapped = !this.wrap || this._largeFileMode;
			if (unwrapped) {
				this._offsets = [];
				this._offsetMetrics = null;
				this._changedLineRange = null;
			} else this._buildOffsets(metrics);
			const scrollTop = this._editor.scrollTop || 0;
			const height = this._editor.clientHeight || this.clientHeight || 300;
			const offsetAt = unwrapped ? (index) => index * metrics.lineHeight : (index) => this._offsets[index];
			let low;
			if (unwrapped) low = Math.min(this._lines.length - 1, Math.max(0, Math.floor(scrollTop / metrics.lineHeight)));
			else {
				let high = this._lines.length;
				low = 0;
				while (low < high) {
					const middle = low + high >> 1;
					if (this._offsets[middle + 1] < scrollTop) low = middle + 1;
					else high = middle;
				}
			}
			const start = Math.max(0, low - 8);
			const end = Math.min(this._lines.length, low + Math.ceil(height / metrics.lineHeight) + 20);
			const code = [], numbers = [];
			for (let index = start; index < end; index++) {
				const row = document.createElement("span");
				row.setAttribute("part", "code-line");
				row.style.position = "absolute";
				row.style.top = offsetAt(index) - offsetAt(start) + "px";
				row.style.width = this.wrap && !this._largeFileMode ? "calc(100% - 1.5rem)" : "max-content";
				row.style.minWidth = this.wrap && !this._largeFileMode ? "" : "100%";
				for (const token of this._tokensFor(index)) {
					const span = document.createElement("span");
					span.setAttribute("part", "code-token");
					span.setAttribute("data-type", token.type);
					span.textContent = token.text;
					row.append(span);
				}
				code.push(row);
				if (this.lineNumbers) {
					const number = document.createElement("span");
					number.setAttribute("part", "line-number");
					number.style.position = "absolute";
					number.style.top = offsetAt(index) - offsetAt(start) + "px";
					number.textContent = String(index + 1);
					numbers.push(number);
				}
			}
			this._linesLayer.replaceChildren(...code);
			this._numbers.replaceChildren(...numbers);
			this._linesLayer.style.transform = "translate(" + (unwrapped ? -(this._editor.scrollLeft || 0) : 0) + "px," + (offsetAt(start) - scrollTop) + "px)";
			this._numbers.style.transform = "translateY(" + (offsetAt(start) - scrollTop) + "px)";
		}
	};
	function defineCopyButtonView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit CopyButtonView requires a browser with Custom Elements");
		const existing = registry.get(COPYBUTTONVIEW_TAG_NAME);
		if (existing && existing !== BiscuitCopyButtonView) throw new Error(`<${COPYBUTTONVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(COPYBUTTONVIEW_TAG_NAME, BiscuitCopyButtonView);
		return BiscuitCopyButtonView;
	}
	function defineSuggestionView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit SuggestionsView requires a browser with Custom Elements");
		const existing = registry.get(SUGGESTIONVIEW_TAG_NAME);
		if (existing && existing !== BiscuitSuggestionView) throw new Error(`<${SUGGESTIONVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(SUGGESTIONVIEW_TAG_NAME, BiscuitSuggestionView);
		return BiscuitSuggestionView;
	}
	function defineCodeView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit CodeView requires a browser with Custom Elements");
		defineCopyButtonView();
		defineSuggestionView();
		const existing = registry.get(CODEVIEW_TAG_NAME);
		if (existing && existing !== BiscuitCodeView) throw new Error(`<${CODEVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(CODEVIEW_TAG_NAME, BiscuitCodeView);
		return BiscuitCodeView;
	}
	function defineCodeExampleView() {
		const registry = globalThis.customElements;
		if (!registry) throw new Error("Biscuit CodeExampleView requires a browser with Custom Elements");
		defineCodeView();
		const existing = registry.get(CODEEXAMPLEVIEW_TAG_NAME);
		if (existing && existing !== BiscuitCodeExampleView) throw new Error(`<${CODEEXAMPLEVIEW_TAG_NAME}> is already registered by another library`);
		if (!existing) registry.define(CODEEXAMPLEVIEW_TAG_NAME, BiscuitCodeExampleView);
		return BiscuitCodeExampleView;
	}
	function createCopyButtonView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit CopyButtonView requires a browser document");
		defineCopyButtonView();
		const element = createConfiguredElement(COPYBUTTONVIEW_TAG_NAME, options);
		for (const key of [
			"label",
			"text",
			"target",
			"htmlFor",
			"disabled",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		return element;
	}
	function createSuggestionView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit SuggestionsView requires a browser document");
		defineSuggestionView();
		const element = createConfiguredElement(SUGGESTIONVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (Object.hasOwn(options, "items")) element.items = options.items;
		if (Object.hasOwn(options, "activeIndex")) element.activeIndex = options.activeIndex;
		return element;
	}
	function createCodeView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit CodeView requires a browser document");
		defineCodeView();
		const element = createConfiguredElement(CODEVIEW_TAG_NAME, options);
		for (const key of [
			"defaultValue",
			"language",
			"editable",
			"lineNumbers",
			"lineNumbersContainerPosition",
			"lineNumberPosition",
			"wrap",
			"tabSize",
			"smartPairs",
			"experimentalCompletion",
			"completionProvider",
			"warnLargeFiles",
			"resizable",
			"limitResizeToParent",
			"copyable",
			"name",
			"disabled",
			"theme"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		if (Object.hasOwn(options, "value")) element.value = options.value;
		if (options.toolbarStart != null) {
			if (typeof options.toolbarStart.setAttribute !== "function") throw new TypeError("CodeView toolbarStart must be an element");
			options.toolbarStart.setAttribute("slot", "toolbar-start");
			element.append(options.toolbarStart);
		}
		return element;
	}
	function createCodeExampleView(options = {}) {
		if (!globalThis.document) throw new Error("Biscuit CodeExampleView requires a browser document");
		defineCodeExampleView();
		const element = createConfiguredElement(CODEEXAMPLEVIEW_TAG_NAME, options);
		if (Object.hasOwn(options, "label")) element.label = options.label;
		if (Object.hasOwn(options, "theme")) element.theme = options.theme;
		if (options.codeView != null) {
			if (options.codeView.localName !== CODEVIEW_TAG_NAME && options.codeView.tagName?.toLowerCase() !== CODEVIEW_TAG_NAME) throw new TypeError("CodeExampleView codeView must be a CodeView element");
			element.append(options.codeView);
		} else if (Object.hasOwn(options, "code")) element.append(createCodeView({
			value: options.code,
			language: options.language ?? "plain",
			editable: false,
			copyable: true
		}));
		if (Object.hasOwn(options, "open")) element.open = options.open;
		return element;
	}
	function defineCode() {
		defineCopyButtonView();
		defineSuggestionView();
		defineCodeExampleView();
		defineCodeView();
	}
	// src/extra/tree.js
	var treeId = 0;
	var itemId = (item, path) => String(item.id ?? item.value ?? path.join("-"));
	var BiscuitTreeView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["label", "value"];
		}
		constructor() {
			super();
			this._items = null;
			this._expanded = /* @__PURE__ */ new Set();
			this._active = null;
			this._value = "";
			this._visible = [];
			this._typeahead = "";
			const root = this.attachShadow({ mode: "open" });
			style(root, `
      :host { display:block; min-width:0; }
      [part=tree] { border:1px solid ${border}; border-radius:.6rem; background:${surface}; padding:calc(.3rem * var(--biscuit-space-scale,1)); max-height:var(--biscuit-tree-height,24rem); overflow:auto; }
      [part=item] { display:flex; align-items:center; min-height:2rem; padding:calc(.2rem * var(--biscuit-space-scale,1)) .4rem; padding-inline-start:calc(.4rem + (var(--depth) - 1) * 1.2rem); border-radius:.35rem; cursor:pointer; outline:none; }
      [part=item][data-active] { background:var(--biscuit-tree-active-background,light-dark(#efedff,#45415f)); color:var(--biscuit-tree-active-color,light-dark(#38306e,#fff)); }
      [part=item][aria-disabled=true] { opacity:.5; cursor:default; }
      [part=toggle] { display:inline-grid; place-items:center; width:1.35rem; height:1.35rem; flex:none; border:0; background:transparent; color:inherit; cursor:pointer; padding:0; margin-inline-end:.2rem; }
      [part=toggle]:disabled { cursor:default; }
      [part=label] { min-width:0; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
    `);
			this._tree = document.createElement("div");
			this._tree.id = `biscuit-tree-${++treeId}`;
			this._tree.setAttribute("part", "tree");
			this._tree.setAttribute("role", "tree");
			this._tree.addEventListener("keydown", (e) => this._keyDown(e));
			root.append(this._tree);
		}
		connectedCallback() {
			this._render();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver(() => {
					if (!this._items) this._render();
				});
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					characterData: true,
					attributes: true,
					attributeFilter: [
						"id",
						"value",
						"label",
						"expanded",
						"disabled",
						"selected"
					]
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			clearTimeout(this._typeTimer);
		}
		attributeChangedCallback() {
			if (this._tree) this._render();
		}
		get items() {
			return this._items;
		}
		set items(value) {
			this._items = Array.isArray(value) ? value : null;
			this._expanded.clear();
			const visit = (nodes, path = []) => nodes.forEach((item, i) => {
				const next = [...path, i];
				if (item.expanded) this._expanded.add(itemId(item, next));
				if (item.children) visit(item.children, next);
			});
			if (this._items) visit(this._items);
			this._render();
		}
		get value() {
			return this._value;
		}
		set value(value) {
			this._value = String(value ?? "");
			this._render();
		}
		get activeId() {
			return this._active;
		}
		focus(options) {
			this._tree.querySelector("[data-active]")?.focus(options);
		}
		_source() {
			if (this._items) return this._items;
			const read = (nodes) => Array.from(nodes).filter((node) => node.tagName === "BISCUIT-TREEITEM").map((node) => ({
				id: node.getAttribute("value") || node.id || void 0,
				label: node.getAttribute("label") ?? node.firstChild?.textContent?.trim() ?? "",
				disabled: node.hasAttribute("disabled"),
				expanded: node.hasAttribute("expanded"),
				selected: node.hasAttribute("selected"),
				children: read(node.children),
				source: node
			}));
			return read(this.children);
		}
		_flatten() {
			const visible = [];
			const walk = (items, depth = 1, parent = null, path = []) => items.forEach((item, index) => {
				const next = [...path, index];
				const id = itemId(item, next);
				const hasChildren = Boolean(item.children?.length);
				const expanded = item.source ? item.source.hasAttribute("expanded") : this._expanded.has(id);
				visible.push({
					item,
					id,
					depth,
					parent,
					index,
					setsize: items.length,
					hasChildren,
					expanded
				});
				if (hasChildren && expanded) walk(item.children, depth + 1, id, next);
			});
			walk(this._source());
			return visible;
		}
		_render() {
			if (!this._tree) return;
			const focusedId = this.shadowRoot?.activeElement?.dataset?.id;
			this._visible = this._flatten();
			if (!this._value) this._value = this.getAttribute("value") ?? this._visible.find((node) => node.item.selected)?.id ?? "";
			if (!this._visible.some((node) => node.id === this._active && !node.item.disabled)) this._active = this._visible.find((node) => !node.item.disabled)?.id ?? null;
			this._tree.setAttribute("aria-label", this.label || "Tree");
			this._tree.replaceChildren(...this._visible.map((node, index) => {
				const row = document.createElement("div");
				row.id = `${this._tree.id}-item-${index}`;
				row.setAttribute("part", "item");
				row.setAttribute("role", "treeitem");
				row.setAttribute("aria-level", String(node.depth));
				row.setAttribute("aria-posinset", String(node.index + 1));
				row.setAttribute("aria-setsize", String(node.setsize));
				row.setAttribute("aria-selected", String(node.id === this._value));
				if (node.hasChildren) row.setAttribute("aria-expanded", String(node.expanded));
				if (node.item.disabled) row.setAttribute("aria-disabled", "true");
				row.dataset.id = node.id;
				row.style.setProperty("--depth", String(node.depth));
				row.tabIndex = node.id === this._active ? 0 : -1;
				if (node.id === this._active) row.setAttribute("data-active", "");
				const toggle = document.createElement("button");
				toggle.type = "button";
				toggle.setAttribute("part", "toggle");
				toggle.tabIndex = -1;
				toggle.setAttribute("aria-hidden", "true");
				toggle.textContent = node.hasChildren ? node.expanded ? "▾" : "▸" : "";
				toggle.disabled = !node.hasChildren || Boolean(node.item.disabled);
				toggle.addEventListener("click", (e) => {
					e.stopPropagation();
					this._toggle(node);
					this._focus(node.id);
				});
				const label = document.createElement("span");
				label.setAttribute("part", "label");
				label.textContent = String(node.item.label ?? node.id);
				row.append(toggle, label);
				row.addEventListener("focus", () => this._activate(node.id));
				row.addEventListener("click", () => {
					if (!node.item.disabled) {
						this._activate(node.id);
						this._select(node);
					}
				});
				return row;
			}));
			if (focusedId) this._focus(focusedId);
		}
		_activate(id) {
			this._active = id;
			for (const row of this._tree.querySelectorAll("[role=treeitem]")) {
				const active = row.dataset.id === id;
				row.tabIndex = active ? 0 : -1;
				row.toggleAttribute("data-active", active);
			}
		}
		_focus(id) {
			this._tree.querySelectorAll("[role=treeitem]").forEach((row) => {
				if (row.dataset.id === id) row.focus();
			});
		}
		_toggle(node, force) {
			if (!node?.hasChildren || node.item.disabled) return;
			const expanded = force ?? !node.expanded;
			if (node.item.source) node.item.source.toggleAttribute("expanded", expanded);
			else if (expanded) this._expanded.add(node.id);
			else this._expanded.delete(node.id);
			this._render();
			event(this, "expandchange", {
				id: node.id,
				item: node.item,
				expanded
			});
		}
		_select(node) {
			if (!node || node.item.disabled) return;
			const previous = this._value;
			this._value = node.id;
			for (const row of this._tree.querySelectorAll("[role=treeitem]")) row.setAttribute("aria-selected", String(row.dataset.id === node.id));
			if (previous !== node.id) event(this, "change", {
				value: node.id,
				item: node.item
			});
		}
		_keyDown(e) {
			const nodes = this._visible.filter((node) => !node.item.disabled);
			const index = nodes.findIndex((node) => node.id === this._active);
			const current = nodes[index];
			if (!current) return;
			let target = null;
			if (e.key === "ArrowDown") target = nodes[Math.min(nodes.length - 1, index + 1)];
			else if (e.key === "ArrowUp") target = nodes[Math.max(0, index - 1)];
			else if (e.key === "Home") target = nodes[0];
			else if (e.key === "End") target = nodes.at(-1);
			else if (e.key === "ArrowRight") {
				if (current.hasChildren && !current.expanded) this._toggle(current, true);
				else if (current.hasChildren) target = nodes.find((node) => node.parent === current.id);
			} else if (e.key === "ArrowLeft") {
				if (current.hasChildren && current.expanded) this._toggle(current, false);
				else target = nodes.find((node) => node.id === current.parent);
			} else if (e.key === "Enter" || e.key === " ") this._select(current);
			else if (e.key.length === 1 && !e.ctrlKey && !e.metaKey && !e.altKey) {
				clearTimeout(this._typeTimer);
				this._typeahead += e.key.toLocaleLowerCase();
				this._typeTimer = setTimeout(() => {
					this._typeahead = "";
				}, 500);
				target = [...nodes.slice(index + 1), ...nodes.slice(0, index + 1)].find((node) => String(node.item.label ?? "").toLocaleLowerCase().startsWith(this._typeahead));
			} else return;
			e.preventDefault();
			if (target) this._focus(target.id);
			else if (e.key === "ArrowRight" || e.key === "ArrowLeft") this._focus(current.id);
		}
	};
	stringProperty(BiscuitTreeView, "label");
	themeProperty(BiscuitTreeView);
	var defineTreeView = () => define("biscuit-treeview", BiscuitTreeView);
	var createTreeView = (source) => {
		defineTreeView();
		return options(document.createElement("biscuit-treeview"), source ?? {}, [
			"label",
			"items",
			"value",
			"theme"
		]);
	};
	// src/extra/navigation.js
	var navigation_exports = /* @__PURE__ */ __exportAll({
		BiscuitAccordionView: () => BiscuitAccordionView,
		BiscuitBreadcrumbView: () => BiscuitBreadcrumbView,
		BiscuitStepperView: () => BiscuitStepperView,
		BiscuitTreeView: () => BiscuitTreeView,
		createAccordionView: () => createAccordionView,
		createBreadcrumbView: () => createBreadcrumbView,
		createStepperView: () => createStepperView,
		createTreeView: () => createTreeView,
		defineAccordionView: () => defineAccordionView,
		defineBreadcrumbView: () => defineBreadcrumbView,
		defineNavigation: () => defineNavigation,
		defineStepperView: () => defineStepperView,
		defineTreeView: () => defineTreeView
	});
	var BiscuitAccordionView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["multiple"];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host { display:block; border:1px solid ${border}; border-radius:.65rem; overflow:hidden; background:${surface}; } [part=item] + [part=item] { border-top:1px solid ${border}; } [part=trigger] { width:100%; text-align:left; border:0; background:transparent; color:inherit; padding:calc(.8rem * var(--biscuit-space-scale,1)) calc(1rem * var(--biscuit-space-scale,1)); cursor:pointer; } [part=panel] { padding:0 calc(1rem * var(--biscuit-space-scale,1)) calc(1rem * var(--biscuit-space-scale,1)); }`);
			this._body = document.createElement("div");
			root.append(this._body);
		}
		connectedCallback() {
			this._render();
			this._observer = new MutationObserver(() => this._render());
			this._observer.observe(this, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: [
					"label",
					"open",
					"disabled"
				]
			});
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		attributeChangedCallback() {
			this._render();
		}
		get items() {
			return Array.from(this.children).filter((node) => node.tagName === "BISCUIT-ACCORDIONITEM");
		}
		_render() {
			this._body.replaceChildren(...this.items.map((item, index) => {
				const box = document.createElement("div");
				box.setAttribute("part", "item");
				const trigger = document.createElement("button");
				trigger.type = "button";
				trigger.setAttribute("part", "trigger");
				trigger.textContent = item.getAttribute("label") ?? `Item ${index + 1}`;
				trigger.disabled = item.hasAttribute("disabled");
				trigger.setAttribute("aria-expanded", String(item.hasAttribute("open")));
				const panel = document.createElement("div");
				panel.setAttribute("part", "panel");
				panel.hidden = !item.hasAttribute("open");
				const slot = document.createElement("slot");
				slot.name = `accordion-${index}`;
				item.setAttribute("slot", slot.name);
				panel.append(slot);
				trigger.addEventListener("click", () => this.toggle(index));
				trigger.addEventListener("keydown", (e) => {
					const buttons = Array.from(this._body.querySelectorAll("button:not(:disabled)"));
					const at = buttons.indexOf(trigger);
					if (e.key === "ArrowDown" || e.key === "ArrowUp") {
						e.preventDefault();
						buttons[(at + (e.key === "ArrowDown" ? 1 : -1) + buttons.length) % buttons.length]?.focus();
					}
					if (e.key === "Home") {
						e.preventDefault();
						buttons[0]?.focus();
					}
					if (e.key === "End") {
						e.preventDefault();
						buttons.at(-1)?.focus();
					}
				});
				box.append(trigger, panel);
				return box;
			}));
		}
		toggle(index) {
			const target = this.items[index];
			if (!target || target.hasAttribute("disabled")) return;
			const next = !target.hasAttribute("open");
			if (next && !this.multiple) {
				for (const item of this.items) if (item !== target) item.removeAttribute("open");
			}
			target.toggleAttribute("open", next);
			this._render();
			event(this, "change", {
				index,
				open: next
			});
		}
	};
	boolProperty(BiscuitAccordionView, "multiple");
	themeProperty(BiscuitAccordionView);
	var BiscuitBreadcrumbView = class extends HTMLElementBase {
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host { display:block; } nav { display:flex; align-items:center; flex-wrap:wrap; gap:calc(.45rem * var(--biscuit-space-scale,1)); } a { color:var(--biscuit-focus-color,#786fc6); } [part=separator] { opacity:.55; }`);
			this._nav = document.createElement("nav");
			this._nav.setAttribute("part", "nav");
			this._nav.setAttribute("aria-label", "Breadcrumb");
			root.append(this._nav);
		}
		connectedCallback() {
			this._render();
			this._observer = new MutationObserver(() => this._render());
			this._observer.observe(this, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: ["href", "aria-current"]
			});
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		_render() {
			const links = Array.from(this.children).filter((node) => node.tagName === "A");
			this._nav.replaceChildren(...links.flatMap((link, index) => {
				const slot = document.createElement("slot");
				slot.name = `crumb-${index}`;
				link.setAttribute("slot", slot.name);
				if (index === links.length - 1 && !link.hasAttribute("aria-current")) link.setAttribute("aria-current", "page");
				if (index === 0) return [slot];
				const separator = document.createElement("span");
				separator.setAttribute("part", "separator");
				separator.setAttribute("aria-hidden", "true");
				separator.textContent = "/";
				return [separator, slot];
			}));
		}
	};
	themeProperty(BiscuitBreadcrumbView);
	var BiscuitStepperView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["current"];
		}
		constructor() {
			super();
			this._items = null;
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{display:block} ol{display:flex;gap:calc(.5rem * var(--biscuit-space-scale,1));list-style:none;padding:0;margin:0;flex-wrap:wrap} li{display:flex;align-items:center;gap:calc(.45rem * var(--biscuit-space-scale,1));opacity:.65} li[data-status=current],li[data-status=complete]{opacity:1} [part=marker]{display:grid;place-items:center;width:1.7rem;height:1.7rem;border:1px solid ${border};border-radius:50%} li[data-status=current] [part=marker],li[data-status=complete] [part=marker]{background:var(--biscuit-focus-color,var(--biscuit-theme-focus-color,#786fc6));color:var(--biscuit-stepper-active-color,var(--biscuit-on-accent,#fff));border-color:transparent} button{border:0;background:none;color:inherit;cursor:pointer;padding:calc(.2rem * var(--biscuit-space-scale,1))} button:disabled{cursor:default}`);
			this._list = document.createElement("ol");
			this._list.setAttribute("part", "steps");
			root.append(this._list);
		}
		connectedCallback() {
			this._render();
			this._observer = new MutationObserver(() => this._render());
			this._observer.observe(this, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: ["label", "status"]
			});
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		attributeChangedCallback() {
			this._render();
		}
		get items() {
			return this._items ?? Array.from(this.children).filter((node) => node.tagName === "BISCUIT-STEP").map((node) => ({
				label: node.getAttribute("label") ?? node.textContent,
				status: node.getAttribute("status")
			}));
		}
		set items(value) {
			this._items = Array.isArray(value) ? value : null;
			this._render();
		}
		get current() {
			return Number(this.getAttribute("current")) || 0;
		}
		set current(value) {
			this.setAttribute("current", String(value));
		}
		_render() {
			const items = this.items;
			this._list.replaceChildren(...items.map((item, index) => {
				const li = document.createElement("li");
				const status = item.status ?? (index < this.current ? "complete" : index === this.current ? "current" : "upcoming");
				li.dataset.status = status;
				li.setAttribute("part", "step");
				if (status === "current") li.setAttribute("aria-current", "step");
				const marker = document.createElement("span");
				marker.setAttribute("part", "marker");
				marker.textContent = status === "complete" ? "✓" : String(index + 1);
				const button = document.createElement("button");
				button.type = "button";
				button.textContent = String(item.label ?? `Step ${index + 1}`);
				button.disabled = !this.hasAttribute("interactive");
				button.addEventListener("click", () => event(this, "stepselect", {
					index,
					item
				}));
				li.append(marker, button);
				return li;
			}));
		}
	};
	boolProperty(BiscuitStepperView, "interactive");
	themeProperty(BiscuitStepperView);
	var defineAccordionView = () => define("biscuit-accordionview", BiscuitAccordionView);
	var defineBreadcrumbView = () => define("biscuit-breadcrumbview", BiscuitBreadcrumbView);
	var defineStepperView = () => define("biscuit-stepperview", BiscuitStepperView);
	function defineNavigation() {
		defineAccordionView();
		defineBreadcrumbView();
		defineStepperView();
		defineTreeView();
	}
	var createAccordionView = (source) => {
		defineAccordionView();
		const element = options(document.createElement("biscuit-accordionview"), source ?? {}, ["multiple", "theme"]);
		for (const item of source?.items ?? []) {
			const node = document.createElement("biscuit-accordionitem");
			node.setAttribute("label", String(item.label));
			if (item.open) node.setAttribute("open", "");
			if (item.disabled) node.setAttribute("disabled", "");
			if (item.content != null) node.append(item.content);
			element.append(node);
		}
		return element;
	};
	var createBreadcrumbView = (source) => {
		defineBreadcrumbView();
		const element = options(document.createElement("biscuit-breadcrumbview"), source ?? {}, ["theme"]);
		for (const item of source?.items ?? []) {
			const a = document.createElement("a");
			a.href = String(item.href ?? "#");
			a.textContent = String(item.label);
			if (item.current) a.setAttribute("aria-current", "page");
			element.append(a);
		}
		return element;
	};
	var createStepperView = (source) => {
		defineStepperView();
		return options(document.createElement("biscuit-stepperview"), source ?? {}, [
			"items",
			"current",
			"interactive",
			"theme"
		]);
	};
	// src/extra/combobox.js
	var listId = 0;
	var normalize$1 = (item) => typeof item === "string" ? {
		value: item,
		label: item,
		disabled: false
	} : {
		value: String(item.value ?? item.label ?? ""),
		label: String(item.label ?? item.value ?? ""),
		disabled: Boolean(item.disabled)
	};
	var BiscuitComboBoxView = class extends HTMLElementBase {
		static formAssociated = true;
		static get observedAttributes() {
			return [
				"name",
				"value",
				"label",
				"placeholder",
				"required",
				"disabled",
				"allow-custom",
				"aria-label"
			];
		}
		constructor() {
			super();
			this._internals = this.attachInternals?.() ?? null;
			if (!this._internals?.setFormValue) this._internals = null;
			this._items = null;
			this._results = [];
			this._value = "";
			this._active = -1;
			this._open = false;
			this._request = 0;
			this._dataSource = null;
			this._onOutside = (e) => {
				if (!e.composedPath().includes(this)) this._close(true, true);
			};
			const root = this.attachShadow({ mode: "open" });
			style(root, `
      :host { display:inline-block; min-width:12rem; max-width:100%; position:relative; }
      [part=label] { display:block; margin-bottom:calc(.3rem * var(--biscuit-space-scale,1)); font-weight:600; }
      [part=control] { box-sizing:border-box; width:100%; padding:calc(.55rem * var(--biscuit-space-scale,1)) calc(.7rem * var(--biscuit-space-scale,1)); border:1px solid ${border}; border-radius:.5rem; background:${surface}; color:inherit; }
      [part=listbox] { position:absolute; z-index:40; inset-inline:0; top:calc(100% + .25rem); max-height:16rem; overflow:auto; border:1px solid ${border}; border-radius:.55rem; background:${surface}; box-shadow:0 .65rem 1.5rem rgb(0 0 0 / 15%); padding:.2rem; }
      [part=listbox][hidden] { display:none; }
      [part=option], [part=empty] { padding:calc(.45rem * var(--biscuit-space-scale,1)) .6rem; border-radius:.35rem; }
      [part=option] { cursor:pointer; }
      [part=option][data-active] { background:var(--biscuit-option-background,light-dark(#efedff,#45415f)); color:var(--biscuit-option-color,light-dark(#38306e,#fff)); }
      [part=option][aria-disabled=true] { opacity:.5; cursor:default; }
      [part=empty] { opacity:.7; }
      :host([disabled]) { opacity:.55; }
    `);
			this._caption = document.createElement("label");
			this._caption.setAttribute("part", "label");
			this._input = document.createElement("input");
			this._input.type = "text";
			this._input.setAttribute("part", "control");
			this._input.setAttribute("role", "combobox");
			this._input.setAttribute("aria-autocomplete", "list");
			this._input.setAttribute("aria-haspopup", "listbox");
			this._input.setAttribute("aria-expanded", "false");
			this._list = document.createElement("div");
			this._list.id = `biscuit-combobox-list-${++listId}`;
			this._list.setAttribute("part", "listbox");
			this._list.setAttribute("role", "listbox");
			this._list.hidden = true;
			this._input.setAttribute("aria-controls", this._list.id);
			this._caption.append(this._input);
			root.append(this._caption, this._list);
			this._input.addEventListener("input", () => {
				this._value = "";
				this._selectedItem = null;
				this._dirtyValue = true;
				this._syncForm();
				event(this, "input", {
					query: this._input.value,
					value: ""
				});
				this._search(this._input.value);
			});
			this._input.addEventListener("focus", () => this._search(this._input.value));
			this._input.addEventListener("keydown", (e) => this._keyDown(e));
			this._input.addEventListener("blur", () => queueMicrotask(() => {
				if (!this.matches(":focus-within")) this._close(true, true);
			}));
		}
		connectedCallback() {
			this._sync();
			if (globalThis.MutationObserver) {
				this._observer = new MutationObserver((records) => {
					if (this._items || !records.some((record) => record.target.tagName === "OPTION" || [...record.addedNodes, ...record.removedNodes].some((node) => node.nodeType === 1 && (node.tagName === "OPTION" || node.querySelector?.("option"))))) return;
					this._syncSelectedLabel();
					if (this._open) this._search(this._input.value);
				});
				this._observer.observe(this, {
					childList: true,
					subtree: true,
					characterData: true,
					attributes: true,
					attributeFilter: [
						"value",
						"label",
						"selected",
						"disabled"
					]
				});
			}
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._abort?.abort();
			this._close(false);
		}
		attributeChangedCallback() {
			if (this._input) this._sync();
		}
		formDisabledCallback() {
			this._sync();
		}
		formResetCallback() {
			this._dirtyValue = false;
			this._setValue(this.getAttribute("value") ?? this._sourceItems().find((item) => item.selected)?.value ?? "", false);
		}
		get form() {
			return this._internals?.form ?? this.closest?.("form") ?? null;
		}
		get validity() {
			return this._internals?.validity ?? this._input.validity;
		}
		get validationMessage() {
			return this._internals?.validationMessage ?? this._input.validationMessage;
		}
		checkValidity() {
			this._syncForm();
			return this._internals?.checkValidity() ?? (this.disabled || !this.required || Boolean(this._value));
		}
		reportValidity() {
			this._syncForm();
			const valid = this._internals?.reportValidity() ?? this.checkValidity();
			if (!valid) this._input.focus();
			return valid;
		}
		focus(options) {
			this._input.focus(options);
		}
		get value() {
			return this._value;
		}
		set value(value) {
			this._setValue(String(value ?? ""), false);
		}
		get items() {
			return this._items;
		}
		set items(value) {
			this._items = Array.isArray(value) ? value.map(normalize$1) : null;
			this._syncSelectedLabel();
			if (this._open) this._search(this._input.value);
		}
		get dataSource() {
			return this._dataSource;
		}
		set dataSource(value) {
			if (value != null && typeof value !== "function") throw new TypeError("dataSource must be a function");
			this._dataSource = value;
			if (this._open) this._search(this._input.value);
		}
		get query() {
			return this._input.value;
		}
		set query(value) {
			this._input.value = String(value ?? "");
			this._search(this._input.value);
		}
		_sourceItems() {
			return this._items ?? Array.from(this.children).filter((node) => node.tagName === "OPTION").map((node) => ({
				value: node.getAttribute("value") ?? node.textContent.trim(),
				label: node.getAttribute("label") ?? node.textContent.trim(),
				disabled: node.hasAttribute("disabled"),
				selected: node.hasAttribute("selected")
			}));
		}
		_syncSelectedLabel() {
			if (!this._value) return;
			const item = this._sourceItems().find((option) => option.value === this._value) ?? this._selectedItem;
			if (item) this._input.value = item.label;
		}
		_sync() {
			this._caption.firstChild?.nodeType === 3 && this._caption.firstChild.remove();
			const label = this.label || this.getAttribute("aria-label") || "";
			this._caption.prepend(document.createTextNode(this.label));
			this._caption.style.fontWeight = this.label ? "600" : "normal";
			this._input.setAttribute("aria-label", label || "Choose an option");
			this._input.placeholder = this.placeholder;
			this._input.disabled = this.disabled;
			if (this.disabled && this._open) this._close(true);
			if (!this._dirtyValue) this._setValue(this.getAttribute("value") ?? this._sourceItems().find((item) => item.selected)?.value ?? "", false);
			else this._syncForm();
		}
		_syncForm() {
			syncFormValue(this, this.name && !this.disabled ? this._value : null);
			const missing = this.required && !this.disabled && !this._value;
			this._internals?.setValidity(missing ? { valueMissing: true } : {}, missing ? "Choose an option." : "", this._input);
			this._input.setAttribute("aria-invalid", String(missing));
		}
		_setValue(value, notify) {
			const old = this._value;
			const match = [...this._sourceItems(), ...this._results].find((item) => item.value === value && !item.disabled);
			this._value = match?.value ?? (this.allowCustom || this._dataSource ? value : "");
			this._selectedItem = match ?? (this._value ? {
				value: this._value,
				label: this._value
			} : null);
			this._input.value = match?.label ?? this._value;
			this._dirtyValue = notify || this._dirtyValue;
			this._syncForm();
			this._close(false);
			if (notify && this._value !== old) event(this, "change", {
				value: this._value,
				item: match ?? null
			});
		}
		_close(restore, commitCustom = false) {
			this._abort?.abort();
			this._request++;
			if (restore && this.allowCustom && commitCustom && this._input.value !== this._value) this._setValue(this._input.value, true);
			else if (restore) this._input.value = this._selectedItem?.label ?? "";
			this._open = false;
			this._list.hidden = true;
			this._input.setAttribute("aria-expanded", "false");
			this._input.setAttribute("aria-busy", "false");
			this._input.removeAttribute("aria-activedescendant");
			document.removeEventListener("pointerdown", this._onOutside, true);
		}
		async _search(query) {
			if (this.disabled || !this.isConnected) return;
			this._abort?.abort();
			const request = ++this._request;
			const controller = new AbortController();
			this._abort = controller;
			const items = this._sourceItems();
			const filter = items.find((item) => item.value === this._value)?.label === query ? "" : query.trim().toLocaleLowerCase();
			this._open = true;
			this._list.hidden = false;
			this._input.setAttribute("aria-expanded", "true");
			this._input.setAttribute("aria-busy", String(Boolean(this._dataSource)));
			document.addEventListener("pointerdown", this._onOutside, true);
			try {
				const result = this._dataSource ? await this._dataSource(query, { signal: controller.signal }) : items.filter((item) => item.label.toLocaleLowerCase().includes(filter));
				if (request !== this._request || controller.signal.aborted || !this.isConnected) return;
				this._results = (Array.isArray(result) ? result : []).map(normalize$1);
				this._input.setAttribute("aria-busy", "false");
				this._active = this._results.findIndex((item) => !item.disabled);
				this._renderResults();
				event(this, "load", {
					query,
					items: this._results
				});
			} catch (error) {
				if (!controller.signal.aborted && request === this._request) {
					this._input.setAttribute("aria-busy", "false");
					event(this, "error", {
						query,
						error
					});
				}
			}
		}
		_renderResults() {
			this._list.replaceChildren(...this._results.map((item, index) => {
				const node = document.createElement("div");
				node.id = `${this._list.id}-option-${index}`;
				node.setAttribute("part", "option");
				node.setAttribute("role", "option");
				node.setAttribute("aria-selected", String(item.value === this._value));
				if (item.disabled) node.setAttribute("aria-disabled", "true");
				node.textContent = item.label;
				if (index === this._active) node.setAttribute("data-active", "");
				node.addEventListener("pointerdown", (e) => e.preventDefault());
				node.addEventListener("pointerenter", () => {
					if (!item.disabled) this._setActive(index);
				});
				node.addEventListener("click", () => {
					if (!item.disabled) {
						this._setValue(item.value, true);
						this._input.focus();
					}
				});
				return node;
			}));
			if (!this._results.length) {
				const empty = document.createElement("div");
				empty.setAttribute("part", "empty");
				empty.textContent = "No matches";
				this._list.append(empty);
			}
			this._setActive(this._active);
		}
		_setActive(index) {
			this._active = index;
			for (const [i, node] of Array.from(this._list.querySelectorAll("[role=option]")).entries()) node.toggleAttribute("data-active", i === index);
			if (index < 0) this._input.removeAttribute("aria-activedescendant");
			else {
				this._input.setAttribute("aria-activedescendant", `${this._list.id}-option-${index}`);
				this._list.children[index]?.scrollIntoView?.({ block: "nearest" });
			}
		}
		_move(amount) {
			const enabled = this._results.map((item, i) => !item.disabled ? i : -1).filter((i) => i >= 0);
			if (!enabled.length) return;
			const at = enabled.indexOf(this._active);
			this._setActive(enabled[(at + amount + enabled.length) % enabled.length]);
		}
		_keyDown(e) {
			if (this.disabled) return;
			if (e.key === "ArrowDown" || e.key === "ArrowUp") {
				e.preventDefault();
				if (!this._open) this._search(this._input.value);
				else this._move(e.key === "ArrowDown" ? 1 : -1);
			} else if (e.key === "Enter" && this._open) {
				e.preventDefault();
				if (this._active >= 0) this._setValue(this._results[this._active].value, true);
				else if (this.allowCustom) this._setValue(this._input.value, true);
				else this._close(true);
			} else if (e.key === "Escape" && this._open) {
				e.preventDefault();
				this._close(true);
			} else if (e.key === "Tab" && this._open) this._close(true, true);
			else if (e.key === "Home" && this._open && !this._input.value) {
				e.preventDefault();
				this._setActive(this._results.findIndex((item) => !item.disabled));
			} else if (e.key === "End" && this._open && !this._input.value) {
				e.preventDefault();
				this._setActive(this._results.findLastIndex((item) => !item.disabled));
			}
		}
	};
	for (const key of [
		"name",
		"label",
		"placeholder"
	]) stringProperty(BiscuitComboBoxView, key);
	for (const key of ["disabled", "required"]) boolProperty(BiscuitComboBoxView, key);
	boolProperty(BiscuitComboBoxView, "allowCustom", "allow-custom");
	themeProperty(BiscuitComboBoxView);
	var defineComboBoxView = () => define("biscuit-comboboxview", BiscuitComboBoxView);
	var createComboBoxView = (source) => {
		defineComboBoxView();
		const element = options(document.createElement("biscuit-comboboxview"), source ?? {}, [
			"name",
			"label",
			"placeholder",
			"required",
			"disabled",
			"allowCustom",
			"theme",
			"items",
			"dataSource"
		]);
		if (source?.value != null) element.setAttribute("value", String(source.value));
		return element;
	};
	// src/extra/forms.js
	var forms_exports = /* @__PURE__ */ __exportAll({
		BiscuitCheckboxView: () => BiscuitCheckboxView,
		BiscuitComboBoxView: () => BiscuitComboBoxView,
		BiscuitFieldView: () => BiscuitFieldView,
		BiscuitFormWizardView: () => BiscuitFormWizardView,
		BiscuitInputView: () => BiscuitInputView,
		BiscuitRadioGroupView: () => BiscuitRadioGroupView,
		BiscuitSliderView: () => BiscuitSliderView,
		BiscuitSwitchView: () => BiscuitSwitchView,
		createCheckboxView: () => createCheckboxView,
		createComboBoxView: () => createComboBoxView,
		createFieldView: () => createFieldView,
		createFormWizardView: () => createFormWizardView,
		createInputView: () => createInputView,
		createRadioGroupView: () => createRadioGroupView,
		createSliderView: () => createSliderView,
		createSwitchView: () => createSwitchView,
		defineCheckboxView: () => defineCheckboxView,
		defineComboBoxView: () => defineComboBoxView,
		defineFieldView: () => defineFieldView,
		defineFormWizardView: () => defineFormWizardView,
		defineForms: () => defineForms,
		defineInputView: () => defineInputView,
		defineRadioGroupView: () => defineRadioGroupView,
		defineSliderView: () => defineSliderView,
		defineSwitchView: () => defineSwitchView
	});
	var controlCss = `
  :host { display: inline-block; min-width: 0; }
  label { display: grid; gap: calc(.3rem * var(--biscuit-space-scale,1)); }
  [part=label] { font-weight: 600; }
  [part=control] { min-width: 0; width: 100%; padding: calc(.55rem * var(--biscuit-space-scale,1)) calc(.7rem * var(--biscuit-space-scale,1)); border: 1px solid ${border}; border-radius: .5rem; background: ${surface}; color: inherit; }
  [part=hint] { color: var(--biscuit-muted,light-dark(#656677,#b8bac6)); font-size: .85em; }
  :host([disabled]) { opacity: .55; }
`;
	var FormControl = class extends HTMLElementBase {
		static formAssociated = true;
		static get observedAttributes() {
			return [
				"name",
				"value",
				"checked",
				"disabled",
				"required",
				"label",
				"hint",
				"placeholder",
				"type",
				"min",
				"max",
				"step",
				"pattern",
				"minlength",
				"maxlength",
				"aria-label"
			];
		}
		constructor(type = "text") {
			super();
			this._internals = this.attachInternals?.() ?? null;
			if (!this._internals?.setFormValue) this._internals = null;
			const root = this.attachShadow({ mode: "open" });
			style(root, controlCss);
			this._label = document.createElement("label");
			this._caption = document.createElement("span");
			this._caption.setAttribute("part", "label");
			this._control = document.createElement("input");
			this._control.type = type;
			this._control.setAttribute("part", "control");
			this._hint = document.createElement("span");
			this._hint.setAttribute("part", "hint");
			this._label.append(this._caption, this._control, this._hint);
			root.append(this._label);
			this._control.addEventListener("input", () => {
				this._fromControl();
				event(this, "input", {
					value: this.value,
					checked: this.checked
				});
			});
			this._control.addEventListener("change", () => {
				this._fromControl();
				event(this, "change", {
					value: this.value,
					checked: this.checked
				});
			});
		}
		connectedCallback() {
			this._sync();
		}
		attributeChangedCallback() {
			if (this._control) this._sync();
		}
		formDisabledCallback() {
			this._sync();
		}
		formResetCallback() {
			this.value = this.getAttribute("value") ?? "";
			if (this._isCheckable) this.checked = this.hasAttribute("checked");
		}
		get form() {
			return this._internals?.form ?? this.closest?.("form") ?? null;
		}
		get validity() {
			return this._control.validity;
		}
		get validationMessage() {
			return this._control.validationMessage;
		}
		checkValidity() {
			this._sync();
			return this._control.checkValidity();
		}
		reportValidity() {
			this._sync();
			return this._control.reportValidity();
		}
		setCustomValidity(message) {
			this._control.setCustomValidity(message);
			syncValidity(this, this._control);
		}
		focus(opts) {
			this._control.focus(opts);
		}
		get value() {
			return this._control.value;
		}
		set value(value) {
			if (this._isCheckable) this.setAttribute("value", String(value ?? "on"));
			this._control.value = String(value ?? "");
			this._syncForm();
		}
		get checked() {
			return this._control.checked;
		}
		set checked(value) {
			this._dirtyChecked = true;
			this._control.checked = Boolean(value);
			this._syncForm();
		}
		_fromControl() {
			this._syncForm();
		}
		_syncForm() {
			syncFormValue(this, this.disabled || this._isCheckable && !this.checked ? null : this.value);
			syncValidity(this, this._control);
		}
		_sync() {
			const c = this._control;
			if (this._isCheckable) {
				if (!this._dirtyChecked) c.checked = this.hasAttribute("checked");
				c.value = this.getAttribute("value") ?? "on";
			} else if (!this._dirtyValue) c.value = this.getAttribute("value") ?? "";
			c.disabled = this.disabled;
			c.required = this.required;
			for (const key of [
				"placeholder",
				"min",
				"max",
				"step",
				"pattern",
				"minLength",
				"maxLength"
			]) {
				const attribute = key.toLowerCase();
				const raw = this.getAttribute(attribute);
				if (raw != null) c.setAttribute(attribute, raw);
				else c.removeAttribute(attribute);
			}
			const label = this.label;
			this._caption.textContent = label;
			this._caption.hidden = !label;
			const accessibleLabel = this.getAttribute("aria-label");
			if (!label && accessibleLabel) c.setAttribute("aria-label", accessibleLabel);
			else c.removeAttribute("aria-label");
			this._hint.textContent = this.hint;
			this._hint.hidden = !this.hint;
			this._syncForm();
		}
	};
	for (const [key, attr] of [
		["name", "name"],
		["label", "label"],
		["hint", "hint"],
		["placeholder", "placeholder"]
	]) stringProperty(FormControl, key, attr);
	for (const key of [
		"min",
		"max",
		"step",
		"pattern",
		"minLength",
		"maxLength"
	]) stringProperty(FormControl, key, key.toLowerCase());
	for (const key of ["disabled", "required"]) boolProperty(FormControl, key);
	themeProperty(FormControl);
	var BiscuitInputView = class extends FormControl {
		constructor() {
			super("text");
		}
		get type() {
			return this.getAttribute("type") ?? "text";
		}
		set type(value) {
			this.setAttribute("type", String(value));
		}
		get value() {
			return this._control.value;
		}
		set value(value) {
			this._dirtyValue = true;
			this._control.value = String(value ?? "");
			this._syncForm();
		}
		_fromControl() {
			this._dirtyValue = true;
			super._fromControl();
		}
		_sync() {
			const type = this.type;
			this._control.type = [
				"text",
				"email",
				"password",
				"number",
				"search",
				"tel",
				"url"
			].includes(type) ? type : "text";
			super._sync();
		}
		formResetCallback() {
			this._dirtyValue = false;
			this._sync();
		}
	};
	var CheckControl = class extends FormControl {
		constructor(type = "checkbox") {
			super(type);
			this._isCheckable = true;
			const sheet = document.createElement("style");
			sheet.textContent = `:host { display:inline-flex; } label { display:flex; align-items:center; gap:calc(.5rem * var(--biscuit-space-scale,1)); cursor:pointer; } [part=control] { width:1.15rem; height:1.15rem; margin:0; padding:0; accent-color:var(--biscuit-focus-color,#786fc6); } [part=label] { order:2; } [part=hint] { order:3; }`;
			this.shadowRoot.append(sheet);
		}
		_fromControl() {
			this._dirtyChecked = true;
			super._fromControl();
		}
		formResetCallback() {
			this._dirtyChecked = false;
			this._sync();
		}
	};
	var BiscuitCheckboxView = class extends CheckControl {};
	var BiscuitSwitchView = class extends CheckControl {
		constructor() {
			super();
			this._control.setAttribute("role", "switch");
			const sheet = document.createElement("style");
			sheet.textContent = `
      [part=control] { appearance:none; width:2.4rem; height:1.3rem; border:1px solid ${border}; border-radius:99px; background:var(--biscuit-switch-track,light-dark(#c8cad3,#5b5f6b)); position:relative; cursor:pointer; }
      [part=control]::before { content:""; position:absolute; width:.9rem; height:.9rem; border-radius:50%; top:.16rem; left:.16rem; background:var(--biscuit-switch-thumb,#fff); transition:transform .15s; }
      [part=control]:checked { background:var(--biscuit-switch-checked-track,var(--biscuit-focus-color,var(--biscuit-theme-focus-color,#786fc6))); }
      [part=control]:checked::before { transform:translateX(1.05rem); background:var(--biscuit-switch-checked-thumb,var(--biscuit-on-accent,#fff)); }
      :host([theme="deep-dark"]) [part=control] { background:var(--biscuit-switch-track,#000); }
      :host([theme="deep-dark"]) [part=control]:checked { background:var(--biscuit-switch-checked-track,#fff); }
      :host([theme="deep-dark"]) [part=control]:checked::before { background:var(--biscuit-switch-checked-thumb,#000); }
      @media(prefers-reduced-motion:reduce) { [part=control]::before { transition:none; } }
    `;
			this.shadowRoot.append(sheet);
		}
	};
	var BiscuitSliderView = class extends FormControl {
		constructor() {
			super("range");
		}
		get value() {
			return this._control.value;
		}
		set value(value) {
			this._dirtyValue = true;
			this._control.value = String(value);
			this._syncForm();
		}
		_sync() {
			super._sync();
			this._control.setAttribute("aria-valuetext", this._control.value);
		}
		_fromControl() {
			this._dirtyValue = true;
			this._control.setAttribute("aria-valuetext", this.value);
			super._fromControl();
		}
		formResetCallback() {
			this._dirtyValue = false;
			this._sync();
		}
	};
	var BiscuitRadioGroupView = class extends HTMLElementBase {
		static formAssociated = true;
		static get observedAttributes() {
			return [
				"name",
				"value",
				"required",
				"disabled",
				"label"
			];
		}
		constructor() {
			super();
			this._internals = this.attachInternals?.() ?? null;
			if (!this._internals?.setFormValue) this._internals = null;
			this._value = "";
			const root = this.attachShadow({ mode: "open" });
			style(root, `${controlCss} [part=options] { display:flex; flex-wrap:wrap; gap:calc(.6rem * var(--biscuit-space-scale,1)) calc(1rem * var(--biscuit-space-scale,1)); } [part=option] { display:inline-flex; align-items:center; gap:calc(.4rem * var(--biscuit-space-scale,1)); cursor:pointer; } [part=control] { width:auto; accent-color:var(--biscuit-focus-color,#786fc6); }`);
			this._caption = document.createElement("div");
			this._caption.setAttribute("part", "label");
			this._options = document.createElement("div");
			this._options.setAttribute("part", "options");
			this._options.setAttribute("role", "radiogroup");
			root.append(this._caption, this._options);
		}
		connectedCallback() {
			this._render();
			this._observer = new MutationObserver(() => this._render());
			this._observer.observe(this, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: [
					"value",
					"label",
					"disabled",
					"checked"
				]
			});
		}
		disconnectedCallback() {
			this._observer?.disconnect();
		}
		attributeChangedCallback() {
			if (this._options) this._render();
		}
		formResetCallback() {
			this.value = this.querySelector("[checked]")?.getAttribute("value") ?? "";
		}
		formDisabledCallback() {
			this._render();
		}
		get value() {
			return this._value;
		}
		set value(value) {
			this._value = String(value ?? "");
			this._render();
		}
		_render() {
			const source = Array.from(this.children).filter((node) => ["BISCUIT-RADIO", "OPTION"].includes(node.tagName));
			const requested = this._value || this.getAttribute("value") || source.find((node) => node.hasAttribute("checked"))?.getAttribute("value") || "";
			this._value = requested;
			this._caption.textContent = this.label;
			this._caption.hidden = !this.label;
			this._options.setAttribute("aria-label", this.label || this.getAttribute("aria-label") || "Options");
			this._options.replaceChildren(...source.map((item, index) => {
				const label = document.createElement("label");
				label.setAttribute("part", "option");
				const input = document.createElement("input");
				input.type = "radio";
				input.name = `biscuit-radio-${this._id ??= Math.random().toString(36).slice(2)}`;
				input.value = item.getAttribute("value") ?? item.textContent.trim();
				input.checked = input.value === requested;
				input.disabled = this.disabled || item.hasAttribute("disabled");
				input.required = this.required;
				input.setAttribute("part", "control");
				input.addEventListener("change", () => {
					this._value = input.value;
					this._syncForm();
					this._render();
					event(this, "input", {
						value: this._value,
						index
					});
					event(this, "change", {
						value: this._value,
						index
					});
				});
				const caption = document.createElement("span");
				caption.textContent = item.getAttribute("label") ?? item.textContent.trim();
				label.append(input, caption);
				return label;
			}));
			this._syncForm();
		}
		_syncForm() {
			syncFormValue(this, this.disabled || !this._value ? null : this._value);
			this._internals?.setValidity(this.required && !this._value ? { valueMissing: true } : {}, this.required && !this._value ? "Choose an option." : "");
		}
		checkValidity() {
			return this._internals?.checkValidity() ?? (!this.required || Boolean(this._value));
		}
		reportValidity() {
			return this._internals?.reportValidity() ?? this.checkValidity();
		}
	};
	for (const key of ["name", "label"]) stringProperty(BiscuitRadioGroupView, key);
	for (const key of ["required", "disabled"]) boolProperty(BiscuitRadioGroupView, key);
	themeProperty(BiscuitRadioGroupView);
	var BiscuitFieldView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"label",
				"hint",
				"error"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host { display:grid; gap:calc(.35rem * var(--biscuit-space-scale,1)); } [part=label] { font-weight:650; } [part=hint] { font-size:.85em; opacity:.75; } [part=error] { color:var(--biscuit-error,#bb3448); font-size:.85em; }`);
			this._label = document.createElement("span");
			this._label.setAttribute("part", "label");
			this._slot = document.createElement("slot");
			this._slot.addEventListener("slotchange", () => this._sync());
			this._hint = document.createElement("span");
			this._hint.setAttribute("part", "hint");
			this._error = document.createElement("span");
			this._error.setAttribute("part", "error");
			this._error.setAttribute("role", "alert");
			root.append(this._label, this._slot, this._hint, this._error);
		}
		connectedCallback() {
			this._sync();
		}
		attributeChangedCallback() {
			this._sync();
		}
		_sync() {
			this._label.textContent = this.label;
			this._label.hidden = !this.label;
			this._hint.textContent = this.hint;
			this._hint.hidden = !this.hint;
			this._error.textContent = this.error;
			this._error.hidden = !this.error;
			const control = Array.from(this.children).find((node) => typeof node.checkValidity === "function");
			if (control && this.label && !control.hasAttribute("aria-label") && !control.hasAttribute("label")) control.setAttribute("aria-label", this.label);
			if (control && (this.error || this.hint)) control.setAttribute("aria-description", this.error || this.hint);
			if (control) {
				if (this.error) control.setAttribute("aria-invalid", "true");
				else control.removeAttribute("aria-invalid");
			}
		}
	};
	for (const key of [
		"label",
		"hint",
		"error"
	]) stringProperty(BiscuitFieldView, key);
	themeProperty(BiscuitFieldView);
	var BiscuitFormWizardView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["step"];
		}
		constructor() {
			super();
			this._step = 0;
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host { display:block; } [part=controls] { display:flex; justify-content:space-between; gap:calc(.75rem * var(--biscuit-space-scale,1)); margin-top:calc(1rem * var(--biscuit-space-scale,1)); } button { padding:calc(.5rem * var(--biscuit-space-scale,1)) calc(1rem * var(--biscuit-space-scale,1)); border:1px solid ${border}; border-radius:.5rem; background:${surface}; color:inherit; cursor:pointer; } button:disabled { opacity:.5; }`);
			this._progress = createStepperView({});
			this._progress.setAttribute("part", "progress");
			this._slot = document.createElement("slot");
			this._slot.addEventListener("slotchange", () => this._render());
			const controls = document.createElement("div");
			controls.setAttribute("part", "controls");
			this._back = document.createElement("button");
			this._back.type = "button";
			this._back.textContent = "Back";
			this._back.addEventListener("click", () => this.back());
			this._next = document.createElement("button");
			this._next.type = "button";
			this._next.textContent = "Next";
			this._next.addEventListener("click", () => this.next());
			controls.append(this._back, this._next);
			root.append(this._progress, this._slot, controls);
		}
		connectedCallback() {
			this._render();
		}
		attributeChangedCallback() {
			this._render();
		}
		get steps() {
			return Array.from(this.children).filter((node) => node.tagName === "BISCUIT-FORMSTEP");
		}
		get currentStep() {
			return this._step;
		}
		set currentStep(value) {
			this.goTo(Number(value));
		}
		get step() {
			return this.steps[this._step]?.getAttribute("value") ?? String(this._step);
		}
		set step(value) {
			const index = this.steps.findIndex((node, i) => (node.getAttribute("value") ?? String(i)) === String(value));
			if (index >= 0) this.goTo(index);
		}
		back() {
			return this.goTo(this._step - 1);
		}
		next() {
			const steps = this.steps;
			if (!steps.length) return false;
			for (const control of steps[this._step].querySelectorAll("input,select,textarea,biscuit-inputview,biscuit-comboboxview,biscuit-checkboxview,biscuit-switchview,biscuit-sliderview,biscuit-radiogroupview,biscuit-dropdownview,biscuit-textview")) if (typeof control.reportValidity === "function" && !control.reportValidity()) {
				control.focus?.();
				return false;
			}
			if (this._step === steps.length - 1) {
				const form = this.closest("form");
				if (form) form.requestSubmit();
				else event(this, "finish", { step: this.step });
				return true;
			}
			return this.goTo(this._step + 1);
		}
		goTo(index) {
			const next = Math.max(0, Math.min(this.steps.length - 1, Number(index)));
			if (!Number.isInteger(next) || next === this._step) return false;
			const detail = {
				from: this._step,
				to: next,
				fromStep: this.step,
				toStep: this.steps[next]?.getAttribute("value") ?? String(next)
			};
			if (!event(this, "beforestepchange", detail, true)) return false;
			this._step = next;
			this._render();
			event(this, "stepchange", {
				step: this.step,
				index: next
			});
			return true;
		}
		_render() {
			const steps = this.steps;
			if (!steps.length) return;
			const requested = this.getAttribute("step");
			if (requested && !this._initialized) {
				const index = steps.findIndex((node, i) => (node.getAttribute("value") ?? String(i)) === requested);
				if (index >= 0) this._step = index;
			}
			this._initialized = true;
			this._step = Math.min(this._step, steps.length - 1);
			for (let i = 0; i < steps.length; i++) {
				steps[i].hidden = i !== this._step;
				steps[i].inert = i !== this._step;
			}
			this._progress.items = steps.map((node, i) => ({
				label: node.getAttribute("label") ?? `Step ${i + 1}`,
				status: i < this._step ? "complete" : i === this._step ? "current" : "upcoming"
			}));
			this._back.disabled = this._step === 0;
			this._next.textContent = this._step === steps.length - 1 ? "Submit" : "Next";
		}
	};
	themeProperty(BiscuitFormWizardView);
	var entries$1 = [
		["inputview", BiscuitInputView],
		["fieldview", BiscuitFieldView],
		["checkboxview", BiscuitCheckboxView],
		["radiogroupview", BiscuitRadioGroupView],
		["switchview", BiscuitSwitchView],
		["sliderview", BiscuitSliderView],
		["formwizardview", BiscuitFormWizardView],
		["comboboxview", BiscuitComboBoxView]
	];
	function defineForms() {
		for (const [tag, ctor] of entries$1) define(`biscuit-${tag}`, ctor);
	}
	var defineInputView = () => define("biscuit-inputview", BiscuitInputView);
	var defineFieldView = () => define("biscuit-fieldview", BiscuitFieldView);
	var defineCheckboxView = () => define("biscuit-checkboxview", BiscuitCheckboxView);
	var defineRadioGroupView = () => define("biscuit-radiogroupview", BiscuitRadioGroupView);
	var defineSwitchView = () => define("biscuit-switchview", BiscuitSwitchView);
	var defineSliderView = () => define("biscuit-sliderview", BiscuitSliderView);
	var defineFormWizardView = () => define("biscuit-formwizardview", BiscuitFormWizardView);
	function factory(tag, source, keys) {
		defineForms();
		return options(document.createElement(`biscuit-${tag}`), source, keys);
	}
	var createInputView = (source) => {
		const element = factory("inputview", source, [
			"name",
			"type",
			"label",
			"hint",
			"placeholder",
			"required",
			"disabled",
			"theme",
			"min",
			"max",
			"step",
			"pattern",
			"minLength",
			"maxLength"
		]);
		if (source?.value != null) element.setAttribute("value", String(source.value));
		return element;
	};
	var createFieldView = (source) => {
		const element = factory("fieldview", source, [
			"label",
			"hint",
			"error",
			"theme"
		]);
		if (source?.content) element.append(source.content);
		return element;
	};
	var createCheckboxView = (source) => {
		const element = factory("checkboxview", source, [
			"name",
			"value",
			"label",
			"hint",
			"required",
			"disabled",
			"theme"
		]);
		if (source?.checked) element.setAttribute("checked", "");
		return element;
	};
	var createRadioGroupView = (source) => {
		const element = factory("radiogroupview", source, [
			"name",
			"value",
			"label",
			"required",
			"disabled",
			"theme"
		]);
		for (const item of source?.items ?? []) {
			const node = document.createElement("biscuit-radio");
			node.setAttribute("value", String(item.value));
			node.textContent = String(item.label ?? item.value);
			if (item.disabled) node.setAttribute("disabled", "");
			element.append(node);
		}
		return element;
	};
	var createSwitchView = (source) => {
		const element = factory("switchview", source, [
			"name",
			"value",
			"label",
			"hint",
			"required",
			"disabled",
			"theme"
		]);
		if (source?.checked) element.setAttribute("checked", "");
		return element;
	};
	var createSliderView = (source) => {
		const element = factory("sliderview", source, [
			"name",
			"label",
			"hint",
			"required",
			"disabled",
			"theme",
			"min",
			"max",
			"step"
		]);
		if (source?.value != null) element.setAttribute("value", String(source.value));
		return element;
	};
	var createFormWizardView = (source) => {
		const element = factory("formwizardview", source, ["theme"]);
		for (const item of source?.steps ?? []) {
			const step = document.createElement("biscuit-formstep");
			step.setAttribute("label", String(item.label));
			if (item.value != null) step.setAttribute("value", String(item.value));
			if (item.content != null) step.append(item.content);
			element.append(step);
		}
		if (source?.step != null) element.step = source.step;
		return element;
	};
	// src/extra/overlays.js
	var overlays_exports = /* @__PURE__ */ __exportAll({
		BiscuitDialogView: () => BiscuitDialogView,
		BiscuitMenuView: () => BiscuitMenuView,
		BiscuitPopoverView: () => BiscuitPopoverView,
		BiscuitToastView: () => BiscuitToastView,
		BiscuitTooltipView: () => BiscuitTooltipView,
		createDialogView: () => createDialogView,
		createMenuView: () => createMenuView,
		createPopoverView: () => createPopoverView,
		createToastView: () => createToastView,
		createTooltipView: () => createTooltipView,
		defineDialogView: () => defineDialogView,
		defineMenuView: () => defineMenuView,
		defineOverlays: () => defineOverlays,
		definePopoverView: () => definePopoverView,
		defineToastView: () => defineToastView,
		defineTooltipView: () => defineTooltipView
	});
	var floatingCss = `:host{display:contents} [part=panel]{position:fixed;z-index:10000;min-width:12rem;max-width:min(28rem,calc(100vw - 16px));max-height:calc(100vh - 16px);overflow:auto;padding:calc(.75rem * var(--biscuit-space-scale,1));border:1px solid ${border};border-radius:.65rem;background:${surface};color:inherit;box-shadow:0 .7rem 2rem #0003} [part=panel][hidden]{display:none}`;
	var FloatingView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["open", "for"];
		}
		constructor() {
			super();
			this._lastFocus = null;
			const root = this.attachShadow({ mode: "open" });
			style(root, floatingCss);
			this._panel = document.createElement("div");
			this._panel.setAttribute("part", "panel");
			this._panel.hidden = true;
			const slot = document.createElement("slot");
			this._panel.append(slot);
			root.append(this._panel);
			this._onDocumentDown = (e) => {
				if (!this.contains(e.target) && !this._panel.contains(e.target) && e.target !== this.anchor) this.hide();
			};
			this._onKey = (e) => {
				if (e.key === "Escape") {
					e.preventDefault();
					this.hide();
				}
			};
			this._onMove = () => this._position();
		}
		connectedCallback() {
			this._sync();
		}
		disconnectedCallback() {
			this._detach();
		}
		attributeChangedCallback() {
			this._sync();
		}
		get anchor() {
			return this._anchor ?? (this.getAttribute("for") ? document.getElementById(this.getAttribute("for")) : null);
		}
		set anchor(value) {
			this._anchor = value;
			this._position();
		}
		get open() {
			return this.hasAttribute("open");
		}
		set open(value) {
			this.toggleAttribute("open", Boolean(value));
		}
		show(anchor) {
			if (anchor) this.anchor = anchor;
			this._lastFocus = document.activeElement;
			this.open = true;
		}
		hide() {
			this.open = false;
		}
		_sync() {
			if (!this._panel) return;
			const active = this.open;
			this._panel.hidden = !active;
			this._panel.setAttribute("aria-hidden", String(!active));
			this._detach();
			if (active) {
				this._position();
				document.addEventListener("pointerdown", this._onDocumentDown, true);
				document.addEventListener("keydown", this._onKey, true);
				window.addEventListener("resize", this._onMove);
				window.addEventListener("scroll", this._onMove, true);
				event(this, "open", { open: true });
			} else if (this._wasOpen) {
				this._lastFocus?.focus?.();
				event(this, "close", { open: false });
			}
			this._wasOpen = active;
		}
		_detach() {
			document.removeEventListener("pointerdown", this._onDocumentDown, true);
			document.removeEventListener("keydown", this._onKey, true);
			window.removeEventListener("resize", this._onMove);
			window.removeEventListener("scroll", this._onMove, true);
		}
		_position() {
			if (this.open) positionPanel(this._panel, this.anchor ?? this);
		}
	};
	themeProperty(FloatingView);
	var BiscuitPopoverView = class extends FloatingView {
		constructor() {
			super();
			this._panel.setAttribute("role", "dialog");
			this._panel.setAttribute("aria-modal", "false");
		}
	};
	var BiscuitMenuView = class extends FloatingView {
		constructor() {
			super();
			this._items = null;
			this._panel.setAttribute("role", "menu");
			this._panel.tabIndex = -1;
			const sheet = document.createElement("style");
			sheet.textContent = `[part=item]{display:block;width:100%;padding:calc(.45rem * var(--biscuit-space-scale,1)) calc(.6rem * var(--biscuit-space-scale,1));border:0;border-radius:.3rem;background:transparent;color:inherit;text-align:left;cursor:pointer}[part=item]:focus-visible,[part=item]:hover{background:var(--biscuit-focus-color,var(--biscuit-theme-focus-color,#786fc6));color:var(--biscuit-on-accent,#fff)}[part=item]:disabled{opacity:.45;cursor:default}[part=separator]{border:0;border-top:1px solid ${border};margin:.3rem 0}`;
			this.shadowRoot.append(sheet);
			this._panel.addEventListener("keydown", (e) => this._key(e));
		}
		connectedCallback() {
			super.connectedCallback();
			this._render();
			this._observer = new MutationObserver(() => this._render());
			this._observer.observe(this, {
				childList: true,
				attributes: true,
				subtree: true
			});
		}
		disconnectedCallback() {
			super.disconnectedCallback();
			this._observer?.disconnect();
		}
		get items() {
			return this._items ?? Array.from(this.children).filter((node) => node.tagName === "BISCUIT-MENUITEM").map((node) => ({
				label: node.getAttribute("label") ?? node.textContent,
				value: node.getAttribute("value") ?? node.textContent,
				disabled: node.hasAttribute("disabled"),
				separator: node.hasAttribute("separator"),
				checked: node.hasAttribute("checked"),
				element: node
			}));
		}
		set items(value) {
			this._items = Array.isArray(value) ? value : null;
			this._render();
		}
		show(anchor) {
			super.show(anchor);
			this._panel.querySelector("[part=item]:not(:disabled)")?.focus();
		}
		_render() {
			if (!this._panel) return;
			this._panel.replaceChildren(...this.items.map((item, index) => {
				if (item.separator) {
					const hr = document.createElement("hr");
					hr.setAttribute("part", "separator");
					hr.setAttribute("role", "separator");
					return hr;
				}
				const button = document.createElement("button");
				button.type = "button";
				button.setAttribute("part", "item");
				button.setAttribute("role", item.checked == null ? "menuitem" : "menuitemcheckbox");
				if (item.checked != null) button.setAttribute("aria-checked", String(item.checked));
				button.textContent = String(item.label ?? item.value);
				button.disabled = Boolean(item.disabled);
				button.addEventListener("click", () => {
					if (item.checked != null) {
						item.checked = !item.checked;
						item.element?.toggleAttribute("checked", item.checked);
						event(this, "change", {
							item,
							index,
							value: item.value,
							checked: item.checked
						});
					}
					event(this, "select", {
						item,
						index,
						value: item.value
					});
					this.hide();
				});
				return button;
			}));
		}
		_key(e) {
			const items = Array.from(this._panel.querySelectorAll("[part=item]:not(:disabled)"));
			const index = items.indexOf(document.activeElement);
			if ([
				"ArrowDown",
				"ArrowUp",
				"Home",
				"End"
			].includes(e.key)) {
				e.preventDefault();
				items[e.key === "Home" ? 0 : e.key === "End" ? items.length - 1 : (index + (e.key === "ArrowDown" ? 1 : -1) + items.length) % items.length]?.focus();
			} else if (e.key === "Enter" || e.key === " ") {
				e.preventDefault();
				document.activeElement?.click?.();
			}
		}
	};
	var BiscuitTooltipView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["for", "text"];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{display:contents}[part=tooltip]{position:fixed;z-index:10001;max-width:18rem;padding:calc(.3rem * var(--biscuit-space-scale,1)) calc(.55rem * var(--biscuit-space-scale,1));border-radius:.35rem;background:light-dark(#292c37,#fff);color:light-dark(#fff,#24252b);font-size:.8rem;pointer-events:none}[part=tooltip][hidden]{display:none}`);
			this._tip = document.createElement("div");
			this._tip.setAttribute("part", "tooltip");
			this._tip.setAttribute("role", "tooltip");
			this._tip.hidden = true;
			root.append(this._tip);
			this._show = () => {
				this._tip.hidden = false;
				positionPanel(this._tip, this._target);
			};
			this._hide = () => {
				this._tip.hidden = true;
			};
		}
		connectedCallback() {
			this._sync();
		}
		disconnectedCallback() {
			this._detach();
		}
		attributeChangedCallback() {
			this._sync();
		}
		_detach() {
			for (const [name, handler] of [
				["mouseenter", this._show],
				["mouseleave", this._hide],
				["focus", this._show],
				["blur", this._hide]
			]) this._target?.removeEventListener(name, handler);
		}
		_sync() {
			this._detach();
			this._target = document.getElementById(this.getAttribute("for"));
			this._tip.textContent = this.text || this.textContent;
			if (!this._tip.id) this._tip.id = `biscuit-tooltip-${Math.random().toString(36).slice(2)}`;
			if (this._target) {
				this._target.setAttribute("aria-describedby", this._tip.id);
				for (const [name, handler] of [
					["mouseenter", this._show],
					["mouseleave", this._hide],
					["focus", this._show],
					["blur", this._hide]
				]) this._target.addEventListener(name, handler);
			}
		}
	};
	stringProperty(BiscuitTooltipView, "text");
	stringProperty(BiscuitTooltipView, "htmlFor", "for");
	themeProperty(BiscuitTooltipView);
	var BiscuitDialogView = class extends HTMLElementBase {
		static get observedAttributes() {
			return ["open", "label"];
		}
		constructor() {
			super();
			this._lastFocus = null;
			const root = this.attachShadow({ mode: "open" });
			style(root, `dialog{width:min(34rem,calc(100vw - 2rem));max-height:calc(100vh - 2rem);padding:calc(1.25rem * var(--biscuit-space-scale,1));border:1px solid ${border};border-radius:.75rem;background:${surface};color:inherit;box-shadow:0 1rem 3rem #0005} dialog::backdrop{background:#0009}[part=header]{display:flex;align-items:center;justify-content:space-between;gap:calc(1rem * var(--biscuit-space-scale,1));margin-bottom:calc(.75rem * var(--biscuit-space-scale,1))}[part=title]{font-size:1.2rem;font-weight:700}[part=close]{border:0;background:none;color:inherit;cursor:pointer;font-size:1.3rem}`);
			this._dialog = document.createElement("dialog");
			this._dialog.setAttribute("part", "dialog");
			const header = document.createElement("div");
			header.setAttribute("part", "header");
			this._title = document.createElement("span");
			this._title.setAttribute("part", "title");
			this._close = document.createElement("button");
			this._close.type = "button";
			this._close.setAttribute("part", "close");
			this._close.setAttribute("aria-label", "Close dialog");
			this._close.textContent = "×";
			this._close.addEventListener("click", () => this.hide());
			header.append(this._title, this._close);
			const slot = document.createElement("slot");
			this._dialog.append(header, slot);
			root.append(this._dialog);
			this._dialog.addEventListener("close", () => {
				if (this.open) this.removeAttribute("open");
				this._lastFocus?.focus?.();
				event(this, "close", { returnValue: this._dialog.returnValue });
			});
			this._dialog.addEventListener("cancel", (e) => {
				e.preventDefault();
				this.hide();
			});
		}
		connectedCallback() {
			this._sync();
		}
		attributeChangedCallback() {
			this._sync();
		}
		get open() {
			return this.hasAttribute("open");
		}
		set open(value) {
			this.toggleAttribute("open", Boolean(value));
		}
		show() {
			this._lastFocus = document.activeElement;
			this.open = true;
		}
		hide(value = "") {
			if (this._dialog.open) this._dialog.close(value);
			else this.open = false;
		}
		_sync() {
			this._title.textContent = this.label;
			this._dialog.setAttribute("aria-label", this.label || "Dialog");
			if (this.open && !this._dialog.open) {
				this._lastFocus ??= document.activeElement;
				this._dialog.showModal();
				event(this, "open", { open: true });
			} else if (!this.open && this._dialog.open) this._dialog.close();
		}
	};
	stringProperty(BiscuitDialogView, "label");
	themeProperty(BiscuitDialogView);
	var BiscuitToastView = class extends HTMLElementBase {
		constructor() {
			super();
			this._counter = 0;
			this._timers = /* @__PURE__ */ new Map();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{position:fixed;right:1rem;bottom:1rem;z-index:10002;display:grid;gap:.5rem;max-width:min(24rem,calc(100vw - 2rem))}[part=toast]{display:flex;align-items:center;gap:calc(.65rem * var(--biscuit-space-scale,1));padding:calc(.65rem * var(--biscuit-space-scale,1)) calc(.8rem * var(--biscuit-space-scale,1));border:1px solid ${border};border-radius:.6rem;background:${surface};color:inherit;box-shadow:0 .4rem 1rem #0003}[part=close]{margin-left:auto;border:0;background:none;color:inherit;cursor:pointer}`);
			this._list = document.createElement("div");
			this._list.setAttribute("part", "list");
			this._list.setAttribute("aria-live", "polite");
			this._list.style.display = "grid";
			this._list.style.gap = ".5rem";
			root.append(this._list);
		}
		show(message, { duration = 4e3, variant = "neutral" } = {}) {
			const id = ++this._counter;
			const item = document.createElement("div");
			item.setAttribute("part", "toast");
			item.dataset.variant = variant;
			item.setAttribute("role", variant === "danger" ? "alert" : "status");
			const text = document.createElement("span");
			text.textContent = String(message);
			const close = document.createElement("button");
			close.type = "button";
			close.setAttribute("part", "close");
			close.setAttribute("aria-label", "Dismiss notification");
			close.textContent = "×";
			close.addEventListener("click", () => this.dismiss(id));
			item.append(text, close);
			item.dataset.id = String(id);
			this._list.append(item);
			if (duration > 0) {
				const start = () => {
					this._timers.set(id, setTimeout(() => this.dismiss(id), duration));
				};
				item.addEventListener("mouseenter", () => clearTimeout(this._timers.get(id)));
				item.addEventListener("mouseleave", start);
				item.addEventListener("focusin", () => clearTimeout(this._timers.get(id)));
				item.addEventListener("focusout", start);
				start();
			}
			event(this, "open", {
				id,
				message,
				variant
			});
			return id;
		}
		dismiss(id) {
			clearTimeout(this._timers.get(id));
			this._timers.delete(id);
			const item = Array.from(this._list.children).find((node) => node.dataset.id === String(id));
			if (!item) return;
			item.remove();
			event(this, "close", { id });
		}
		disconnectedCallback() {
			for (const timer of this._timers.values()) clearTimeout(timer);
			this._timers.clear();
		}
	};
	themeProperty(BiscuitToastView);
	var defineDialogView = () => define("biscuit-dialogview", BiscuitDialogView);
	var definePopoverView = () => define("biscuit-popoverview", BiscuitPopoverView);
	var defineTooltipView = () => define("biscuit-tooltipview", BiscuitTooltipView);
	var defineMenuView = () => define("biscuit-menuview", BiscuitMenuView);
	var defineToastView = () => define("biscuit-toastview", BiscuitToastView);
	function defineOverlays() {
		defineDialogView();
		definePopoverView();
		defineTooltipView();
		defineMenuView();
		defineToastView();
	}
	var createDialogView = (source) => {
		defineDialogView();
		const node = options(document.createElement("biscuit-dialogview"), source ?? {}, ["label", "theme"]);
		if (source?.content != null) node.append(source.content);
		if (source?.open) node.open = true;
		return node;
	};
	var createPopoverView = (source) => {
		definePopoverView();
		const node = options(document.createElement("biscuit-popoverview"), source ?? {}, ["anchor", "theme"]);
		if (source?.content != null) node.append(source.content);
		if (source?.open) node.open = true;
		return node;
	};
	var createTooltipView = (source) => {
		defineTooltipView();
		return options(document.createElement("biscuit-tooltipview"), source ?? {}, [
			"text",
			"htmlFor",
			"theme"
		]);
	};
	var createMenuView = (source) => {
		defineMenuView();
		return options(document.createElement("biscuit-menuview"), source ?? {}, [
			"items",
			"anchor",
			"theme"
		]);
	};
	var createToastView = (source) => {
		defineToastView();
		return options(document.createElement("biscuit-toastview"), source ?? {}, ["theme"]);
	};
	// src/extra/data.js
	var data_exports = /* @__PURE__ */ __exportAll({
		BiscuitAvatarView: () => BiscuitAvatarView,
		BiscuitPaginationView: () => BiscuitPaginationView,
		BiscuitProgressView: () => BiscuitProgressView,
		BiscuitSkeletonView: () => BiscuitSkeletonView,
		BiscuitTableView: () => BiscuitTableView,
		createAvatarView: () => createAvatarView,
		createPaginationView: () => createPaginationView,
		createProgressView: () => createProgressView,
		createSkeletonView: () => createSkeletonView,
		createTableView: () => createTableView,
		defineAvatarView: () => defineAvatarView,
		defineData: () => defineData,
		definePaginationView: () => definePaginationView,
		defineProgressView: () => defineProgressView,
		defineSkeletonView: () => defineSkeletonView,
		defineTableView: () => defineTableView
	});
	var BiscuitProgressView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"value",
				"max",
				"label",
				"indeterminate"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{display:block}[part=track]{height:var(--biscuit-progress-height,.65rem);overflow:hidden;border-radius:99px;background:var(--biscuit-track,light-dark(#e4e5ed,#414553))}[part=fill]{height:100%;width:0;background:var(--biscuit-focus-color,#786fc6);border-radius:inherit;transition:width .2s}:host([indeterminate]) [part=fill]{width:35%;animation:move 1.2s infinite alternate}@keyframes move{to{transform:translateX(190%)}}@media(prefers-reduced-motion:reduce){[part=fill]{animation:none!important;transition:none}}`);
			this._track = document.createElement("div");
			this._track.setAttribute("part", "track");
			this._fill = document.createElement("div");
			this._fill.setAttribute("part", "fill");
			this._track.append(this._fill);
			root.append(this._track);
		}
		connectedCallback() {
			this._sync();
		}
		attributeChangedCallback() {
			this._sync();
		}
		_sync() {
			const max = Math.max(1, Number(this.max) || 100);
			const value = Math.max(0, Math.min(max, Number(this.value) || 0));
			this.setAttribute("role", "progressbar");
			this.setAttribute("aria-label", this.label || "Progress");
			this.setAttribute("aria-valuemin", "0");
			this.setAttribute("aria-valuemax", String(max));
			if (this.indeterminate) this.removeAttribute("aria-valuenow");
			else this.setAttribute("aria-valuenow", String(value));
			this._fill.style.width = this.indeterminate ? "35%" : `${value / max * 100}%`;
		}
	};
	for (const key of [
		"value",
		"max",
		"label"
	]) stringProperty(BiscuitProgressView, key);
	boolProperty(BiscuitProgressView, "indeterminate");
	themeProperty(BiscuitProgressView);
	var BiscuitSkeletonView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"width",
				"height",
				"circle"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{display:block;width:var(--biscuit-skeleton-width,100%);height:var(--biscuit-skeleton-height,1.2rem);border-radius:var(--biscuit-skeleton-radius,.4rem);background:linear-gradient(100deg,light-dark(#e4e5eb,#353946) 25%,light-dark(#f4f4f8,#464b58) 50%,light-dark(#e4e5eb,#353946) 75%);background-size:200% 100%;animation:shimmer 1.5s linear infinite}:host([circle]){border-radius:50%}@keyframes shimmer{to{background-position-x:-200%}}@media(prefers-reduced-motion:reduce){:host{animation:none}}`);
			root.append(document.createElement("slot"));
		}
		connectedCallback() {
			this._sync();
		}
		attributeChangedCallback() {
			this._sync();
		}
		_sync() {
			this.style.width = this.width || "";
			this.style.height = this.height || "";
			this.setAttribute("aria-hidden", "true");
		}
	};
	for (const key of ["width", "height"]) stringProperty(BiscuitSkeletonView, key);
	boolProperty(BiscuitSkeletonView, "circle");
	themeProperty(BiscuitSkeletonView);
	var BiscuitPaginationView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"page",
				"total",
				"page-size"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{display:block}nav{display:flex;align-items:center;gap:calc(.4rem * var(--biscuit-space-scale,1));flex-wrap:wrap}button{border:1px solid ${border};border-radius:.4rem;background:${surface};color:inherit;min-width:2.2rem;padding:calc(.3rem * var(--biscuit-space-scale,1)) calc(.5rem * var(--biscuit-space-scale,1));cursor:pointer}button[aria-current=page]{background:var(--biscuit-focus-color,var(--biscuit-theme-focus-color,#786fc6));color:var(--biscuit-on-accent,#fff)}button:disabled{opacity:.5;cursor:default}`);
			this._nav = document.createElement("nav");
			this._nav.setAttribute("part", "pagination");
			this._nav.setAttribute("aria-label", "Pagination");
			root.append(this._nav);
		}
		connectedCallback() {
			this._render();
		}
		attributeChangedCallback() {
			this._render();
		}
		get page() {
			return Math.max(1, Number(this.getAttribute("page")) || 1);
		}
		set page(value) {
			this.setAttribute("page", String(value));
		}
		get total() {
			return Math.max(0, Number(this.getAttribute("total")) || 0);
		}
		set total(value) {
			this.setAttribute("total", String(value));
		}
		get pageSize() {
			return Math.max(1, Number(this.getAttribute("page-size")) || 10);
		}
		set pageSize(value) {
			this.setAttribute("page-size", String(value));
		}
		get pageCount() {
			return Math.max(1, Math.ceil(this.total / this.pageSize));
		}
		goTo(value) {
			const next = Math.min(this.pageCount, Math.max(1, Number(value) || 1));
			if (next === this.page) return;
			this.page = next;
			event(this, "change", {
				page: next,
				pageSize: this.pageSize
			});
		}
		_render() {
			if (!this._nav) return;
			const make = (label, page, disabled = false, current = false) => {
				const button = document.createElement("button");
				button.type = "button";
				button.textContent = label;
				button.disabled = disabled;
				button.setAttribute("aria-label", label === "‹" ? "Previous page" : label === "›" ? "Next page" : `Page ${label}`);
				if (current) button.setAttribute("aria-current", "page");
				button.addEventListener("click", () => this.goTo(page));
				return button;
			};
			const pages = [];
			const first = Math.max(1, Math.min(this.page - 2, this.pageCount - 4));
			for (let p = first; p <= Math.min(this.pageCount, first + 4); p++) pages.push(make(String(p), p, false, p === this.page));
			this._nav.replaceChildren(make("‹", this.page - 1, this.page === 1), ...pages, make("›", this.page + 1, this.page === this.pageCount));
		}
	};
	themeProperty(BiscuitPaginationView);
	var BiscuitAvatarView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"src",
				"alt",
				"initials"
			];
		}
		constructor() {
			super();
			const root = this.attachShadow({ mode: "open" });
			style(root, `:host{display:inline-grid;place-items:center;width:var(--biscuit-avatar-size,2.6rem);height:var(--biscuit-avatar-size,2.6rem);overflow:hidden;border-radius:50%;background:var(--biscuit-avatar-background,light-dark(#e8e6f7,#474363));color:var(--biscuit-avatar-color,inherit);font-weight:700}img{width:100%;height:100%;object-fit:cover}`);
			this._fallback = document.createElement("span");
			this._fallback.setAttribute("part", "fallback");
			this._image = document.createElement("img");
			this._image.setAttribute("part", "image");
			this._image.addEventListener("error", () => {
				this._image.hidden = true;
				this._fallback.hidden = false;
			});
			root.append(this._image, this._fallback);
		}
		connectedCallback() {
			this._sync();
		}
		attributeChangedCallback() {
			this._sync();
		}
		_sync() {
			this._fallback.textContent = this.initials || this.alt.split(/\s+/).map((word) => word[0] || "").slice(0, 2).join("").toUpperCase() || "?";
			this._image.hidden = !this.src;
			this._fallback.hidden = Boolean(this.src);
			if (this.src) this._image.src = this.src;
			this._image.alt = this.alt;
			this.setAttribute("role", "img");
			this.setAttribute("aria-label", this.alt || this.initials || "Avatar");
		}
	};
	for (const key of [
		"src",
		"alt",
		"initials"
	]) stringProperty(BiscuitAvatarView, key);
	themeProperty(BiscuitAvatarView);
	var tableCss = `:host{display:block;min-width:0}[part=viewport]{height:var(--biscuit-table-height,360px);overflow:auto;border:1px solid ${border};border-radius:.5rem;background:${surface};position:relative}[part=header]{position:sticky;top:0;z-index:6;background:${surface};display:flex;min-height:var(--biscuit-row-height,36px);border-bottom:1px solid ${border}}[part=body]{position:relative}[part=row]{position:absolute;left:0;display:flex;height:var(--biscuit-row-height,36px);border-bottom:1px solid ${border}}[part=row][aria-selected=true]{background:var(--biscuit-selection,light-dark(#eceafd,#393550))}[part=cell],[part=columnheader]{height:100%;min-width:0;display:flex;align-items:center;padding:.3rem .55rem;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;border-right:1px solid ${border};background:inherit}[part=columnheader]{font-weight:650;position:relative;cursor:pointer}[part=columnheader]:focus-visible,[part=cell]:focus-visible{outline:2px solid var(--biscuit-focus-color,#786fc6);outline-offset:-2px}[part=resize]{position:absolute;right:0;top:0;bottom:0;width:7px;cursor:col-resize;z-index:2}[part=editor]{width:100%;height:100%;padding:0 .2rem;color:inherit;background:${surface};border:1px solid var(--biscuit-focus-color,#786fc6)}[part=footer]{display:flex;justify-content:space-between;align-items:center;gap:calc(.75rem * var(--biscuit-space-scale,1));margin-top:calc(.4rem * var(--biscuit-space-scale,1));color:var(--biscuit-muted,#777)}[part=empty]{padding:calc(1rem * var(--biscuit-space-scale,1))}`;
	var BiscuitTableView = class extends HTMLElementBase {
		static get observedAttributes() {
			return [
				"page",
				"page-size",
				"filter",
				"sort-key",
				"sort-direction",
				"row-height"
			];
		}
		constructor() {
			super();
			this._columns = [];
			this._rows = [];
			this._total = 0;
			this._selected = /* @__PURE__ */ new Set();
			this._columnWidths = /* @__PURE__ */ new Map();
			this._request = 0;
			this._abort = null;
			this._loaded = [];
			const root = this.attachShadow({ mode: "open" });
			style(root, tableCss);
			this._viewport = document.createElement("div");
			this._viewport.setAttribute("part", "viewport");
			this._viewport.setAttribute("role", "grid");
			this._viewport.tabIndex = 0;
			this._header = document.createElement("div");
			this._header.setAttribute("part", "header");
			this._header.setAttribute("role", "row");
			this._body = document.createElement("div");
			this._body.setAttribute("part", "body");
			this._footer = document.createElement("div");
			this._footer.setAttribute("part", "footer");
			this._viewport.append(this._header, this._body);
			root.append(this._viewport, this._footer);
			this._viewport.addEventListener("scroll", () => this._renderWindow());
			this._viewport.addEventListener("keydown", (e) => this._key(e));
		}
		connectedCallback() {
			if (!this._columns.length) this._readDeclarative();
			this._refresh();
			this._resize = new ResizeObserver(() => this._renderWindow());
			this._resize.observe(this._viewport);
		}
		disconnectedCallback() {
			this._abort?.abort();
			this._resize?.disconnect();
		}
		attributeChangedCallback() {
			if (this.isConnected) this._refresh();
		}
		get columns() {
			return this._columns;
		}
		set columns(value) {
			this._columns = Array.isArray(value) ? value.map((column) => ({ ...column })) : [];
			this._refresh();
		}
		get rows() {
			return this._rows;
		}
		set rows(value) {
			this._rows = Array.isArray(value) ? value : [];
			this._refresh();
		}
		get dataSource() {
			return this._dataSource;
		}
		set dataSource(value) {
			this._dataSource = typeof value === "function" ? value : null;
			this._refresh();
		}
		get updateCell() {
			return this._updateCell;
		}
		set updateCell(value) {
			this._updateCell = typeof value === "function" ? value : null;
		}
		get page() {
			return Math.max(1, Number(this.getAttribute("page")) || 1);
		}
		set page(value) {
			this.setAttribute("page", String(value));
		}
		get pageSize() {
			return Math.max(1, Number(this.getAttribute("page-size")) || 100);
		}
		set pageSize(value) {
			this.setAttribute("page-size", String(value));
		}
		get filter() {
			return this.getAttribute("filter") ?? "";
		}
		set filter(value) {
			this.setAttribute("filter", String(value ?? ""));
		}
		get sortKey() {
			return this.getAttribute("sort-key") ?? "";
		}
		set sortKey(value) {
			this.setAttribute("sort-key", String(value ?? ""));
		}
		get sortDirection() {
			return this.getAttribute("sort-direction") ?? "asc";
		}
		set sortDirection(value) {
			this.setAttribute("sort-direction", String(value));
		}
		get rowHeight() {
			return Math.max(24, Number(this.getAttribute("row-height")) || 36);
		}
		set rowHeight(value) {
			this.setAttribute("row-height", String(value));
		}
		get total() {
			return this._total;
		}
		get selectedKeys() {
			return new Set(this._selected);
		}
		get _width() {
			return this._columns.reduce((sum, col) => sum + this._colWidth(col), 0);
		}
		_colWidth(col) {
			return this._columnWidths.get(col.key) ?? Math.max(60, Number(col.width) || 160);
		}
		_readDeclarative() {
			const columns = Array.from(this.children).filter((node) => node.tagName === "BISCUIT-COLUMN");
			this._columns = columns.map((node) => ({
				key: node.getAttribute("key") || "",
				label: node.getAttribute("label") || node.getAttribute("key") || "",
				width: Number(node.getAttribute("width")) || 160,
				sortable: !node.hasAttribute("no-sort"),
				editable: node.hasAttribute("editable"),
				pinned: node.getAttribute("pinned") || ""
			}));
			this._rows = Array.from(this.children).filter((node) => node.tagName === "BISCUIT-ROW").map((row) => {
				const data = {};
				Array.from(row.children).filter((node) => node.tagName === "BISCUIT-CELL").forEach((cell, index) => {
					data[cell.getAttribute("key") || this._columns[index]?.key || String(index)] = cell.textContent;
				});
				return data;
			});
		}
		async _refresh() {
			if (!this._viewport || !this.isConnected) return;
			this._abort?.abort();
			const request = ++this._request;
			const controller = new AbortController();
			this._abort = controller;
			if (this._dataSource) {
				this._footer.textContent = "Loading…";
				try {
					const result = await this._dataSource({
						offset: (this.page - 1) * this.pageSize,
						limit: this.pageSize,
						sort: this.sortKey ? {
							key: this.sortKey,
							direction: this.sortDirection
						} : null,
						filter: this.filter,
						signal: controller.signal
					});
					if (controller.signal.aborted || request !== this._request) return;
					this._loaded = result.rows ?? [];
					this._total = Number(result.total) || 0;
				} catch (error) {
					if (controller.signal.aborted || request !== this._request) return;
					this._loaded = [];
					this._total = 0;
					this._footer.textContent = "Could not load rows";
					event(this, "error", {
						error,
						operation: "load"
					});
					return;
				}
			} else {
				let rows = this._rows.slice();
				if (this.filter) {
					const needle = this.filter.toLocaleLowerCase();
					rows = rows.filter((row) => Object.values(row).some((value) => String(value ?? "").toLocaleLowerCase().includes(needle)));
				}
				if (this.sortKey) {
					const key = this.sortKey, sign = this.sortDirection === "desc" ? -1 : 1;
					rows.sort((a, b) => sign * String(a[key] ?? "").localeCompare(String(b[key] ?? ""), void 0, {
						numeric: true,
						sensitivity: "base"
					}));
				}
				this._total = rows.length;
				this._loaded = rows.slice((this.page - 1) * this.pageSize, this.page * this.pageSize);
			}
			this._renderHeader();
			this._body.style.height = `${this._loaded.length * this.rowHeight}px`;
			this._body.style.width = `${this._width}px`;
			this._header.style.width = `${this._width}px`;
			this._viewport.style.setProperty("--biscuit-row-height", `${this.rowHeight}px`);
			this._viewport.setAttribute("aria-rowcount", String(this._total));
			this._viewport.setAttribute("aria-colcount", String(this._columns.length));
			this._footer.textContent = `${this._total.toLocaleString()} rows · page ${this.page} of ${Math.max(1, Math.ceil(this._total / this.pageSize))}`;
			this._renderWindow();
			event(this, "load", {
				rows: this._loaded,
				total: this._total,
				page: this.page
			});
		}
		_visibleColumns() {
			const left = this._viewport.scrollLeft, right = left + this._viewport.clientWidth;
			let offset = 0;
			return this._columns.map((col, index) => {
				const width = this._colWidth(col);
				const result = {
					col,
					index,
					offset,
					width
				};
				offset += width;
				return result;
			}).filter((item) => item.col.pinned || item.offset + item.width >= left - 160 && item.offset <= right + 160);
		}
		_sticky(item) {
			if (item.col.pinned === "left") return {
				position: "sticky",
				left: `${this._columns.slice(0, item.index).filter((col) => col.pinned === "left").reduce((n, col) => n + this._colWidth(col), 0)}px`,
				zIndex: "4",
				background: surface
			};
			if (item.col.pinned === "right") return {
				position: "sticky",
				right: `${this._columns.slice(item.index + 1).filter((col) => col.pinned === "right").reduce((n, col) => n + this._colWidth(col), 0)}px`,
				zIndex: "4",
				background: surface
			};
			return {
				position: "absolute",
				left: `${item.offset}px`
			};
		}
		_renderHeader() {
			this._header.replaceChildren(...this._visibleColumns().map((item) => {
				const cell = document.createElement("div");
				cell.setAttribute("part", "columnheader");
				cell.setAttribute("role", "columnheader");
				cell.setAttribute("aria-colindex", String(item.index + 1));
				cell.tabIndex = 0;
				cell.style.width = `${item.width}px`;
				Object.assign(cell.style, this._sticky(item));
				cell.textContent = String(item.col.label ?? item.col.key);
				if (this.sortKey === item.col.key) cell.setAttribute("aria-sort", this.sortDirection === "desc" ? "descending" : "ascending");
				const sort = () => {
					if (item.col.sortable === false) return;
					this.sortDirection = this.sortKey === item.col.key && this.sortDirection === "asc" ? "desc" : "asc";
					this.sortKey = item.col.key;
					event(this, "sortchange", {
						key: item.col.key,
						direction: this.sortDirection
					});
				};
				cell.addEventListener("click", sort);
				cell.addEventListener("keydown", (e) => {
					if (e.key === "Enter" || e.key === " ") {
						e.preventDefault();
						sort();
					}
				});
				const handle = document.createElement("span");
				handle.setAttribute("part", "resize");
				handle.setAttribute("aria-label", `Resize ${item.col.label ?? item.col.key}`);
				handle.addEventListener("click", (e) => e.stopPropagation());
				handle.addEventListener("pointerdown", (e) => {
					e.preventDefault();
					e.stopPropagation();
					const start = e.clientX, initial = item.width;
					const move = (moveEvent) => {
						this._columnWidths.set(item.col.key, Math.max(60, initial + moveEvent.clientX - start));
						this._renderWindow();
					};
					const end = () => {
						window.removeEventListener("pointermove", move);
						window.removeEventListener("pointerup", end);
						event(this, "columnresize", {
							key: item.col.key,
							width: this._colWidth(item.col)
						});
					};
					window.addEventListener("pointermove", move);
					window.addEventListener("pointerup", end, { once: true });
				});
				cell.append(handle);
				return cell;
			}));
		}
		_renderWindow() {
			if (!this._viewport || !this._loaded) return;
			const height = this.rowHeight, start = Math.max(0, Math.floor(this._viewport.scrollTop / height) - 5), end = Math.min(this._loaded.length, Math.ceil((this._viewport.scrollTop + this._viewport.clientHeight) / height) + 5), columns = this._visibleColumns();
			this._header.style.width = `${this._width}px`;
			this._body.style.width = `${this._width}px`;
			this._body.style.height = `${this._loaded.length * height}px`;
			this._renderHeader();
			const fragment = document.createDocumentFragment();
			for (let index = start; index < end; index++) {
				const row = this._loaded[index], key = String(row.id ?? (this.page - 1) * this.pageSize + index);
				const node = document.createElement("div");
				node.setAttribute("part", "row");
				node.setAttribute("role", "row");
				node.setAttribute("aria-rowindex", String((this.page - 1) * this.pageSize + index + 1));
				node.setAttribute("aria-selected", String(this._selected.has(key)));
				node.style.top = `${index * height}px`;
				node.style.width = `${this._width}px`;
				node.addEventListener("click", (e) => {
					if (e.target.closest("[part=editor]")) return;
					this.selectRow(key);
				});
				for (const item of columns) {
					const cell = document.createElement("div");
					cell.setAttribute("part", "cell");
					cell.setAttribute("role", "gridcell");
					cell.setAttribute("aria-colindex", String(item.index + 1));
					cell.tabIndex = 0;
					cell.style.width = `${item.width}px`;
					Object.assign(cell.style, this._sticky(item));
					cell.textContent = String(row[item.col.key] ?? "");
					cell.addEventListener("dblclick", () => this._edit(cell, row, key, item.col, index));
					cell.addEventListener("keydown", (e) => {
						if (e.key === "Enter" && item.col.editable) {
							e.preventDefault();
							this._edit(cell, row, key, item.col, index);
						} else if (e.key === " ") {
							e.preventDefault();
							this.selectRow(key);
						}
					});
					node.append(cell);
				}
				fragment.append(node);
			}
			if (!this._loaded.length) {
				const empty = document.createElement("div");
				empty.setAttribute("part", "empty");
				empty.textContent = "No rows";
				fragment.append(empty);
			}
			this._body.replaceChildren(fragment);
		}
		selectRow(key, selected = !this._selected.has(String(key))) {
			key = String(key);
			if (selected) this._selected.add(key);
			else this._selected.delete(key);
			this._renderWindow();
			event(this, "selectionchange", {
				selectedKeys: [...this._selected],
				key,
				selected
			});
		}
		_edit(cell, row, rowKey, column, rowIndex) {
			if (!column.editable) return;
			const original = row[column.key];
			const input = document.createElement("input");
			input.setAttribute("part", "editor");
			input.setAttribute("aria-label", `Edit ${column.label ?? column.key}`);
			input.value = String(original ?? "");
			cell.replaceChildren(input);
			input.focus();
			input.select();
			let done = false;
			const finish = async (commit) => {
				if (done) return;
				done = true;
				if (!commit || input.value === String(original ?? "")) {
					cell.textContent = String(original ?? "");
					return;
				}
				const value = input.value;
				row[column.key] = value;
				cell.textContent = value;
				event(this, "celledit", {
					rowKey,
					columnKey: column.key,
					value,
					previousValue: original,
					row
				});
				try {
					if (this._updateCell) await this._updateCell({
						rowKey,
						columnKey: column.key,
						value,
						previousValue: original,
						row,
						signal: this._abort?.signal
					});
					event(this, "change", {
						rowKey,
						columnKey: column.key,
						value,
						previousValue: original
					});
				} catch (error) {
					row[column.key] = original;
					cell.textContent = String(original ?? "");
					event(this, "error", {
						error,
						operation: "edit",
						rowKey,
						columnKey: column.key,
						attemptedValue: value
					});
				}
			};
			input.addEventListener("keydown", (e) => {
				if (e.key === "Enter") {
					e.preventDefault();
					finish(true);
				}
				if (e.key === "Escape") {
					e.preventDefault();
					finish(false);
				}
			});
			input.addEventListener("blur", () => finish(true), { once: true });
		}
		_key(e) {
			if (e.key === "PageDown") {
				e.preventDefault();
				this.page++;
				event(this, "pagechange", { page: this.page });
			}
			if (e.key === "PageUp") {
				e.preventDefault();
				this.page = Math.max(1, this.page - 1);
				event(this, "pagechange", { page: this.page });
			}
		}
	};
	themeProperty(BiscuitTableView);
	var entries = [
		["progressview", BiscuitProgressView],
		["skeletonview", BiscuitSkeletonView],
		["paginationview", BiscuitPaginationView],
		["avatarview", BiscuitAvatarView],
		["tableview", BiscuitTableView]
	];
	function defineData() {
		for (const [tag, ctor] of entries) define(`biscuit-${tag}`, ctor);
	}
	var defineProgressView = () => define("biscuit-progressview", BiscuitProgressView);
	var defineSkeletonView = () => define("biscuit-skeletonview", BiscuitSkeletonView);
	var definePaginationView = () => define("biscuit-paginationview", BiscuitPaginationView);
	var defineAvatarView = () => define("biscuit-avatarview", BiscuitAvatarView);
	var defineTableView = () => define("biscuit-tableview", BiscuitTableView);
	var createProgressView = (source) => {
		defineProgressView();
		return options(document.createElement("biscuit-progressview"), source ?? {}, [
			"value",
			"max",
			"label",
			"indeterminate",
			"theme"
		]);
	};
	var createSkeletonView = (source) => {
		defineSkeletonView();
		return options(document.createElement("biscuit-skeletonview"), source ?? {}, [
			"width",
			"height",
			"circle",
			"theme"
		]);
	};
	var createPaginationView = (source) => {
		definePaginationView();
		return options(document.createElement("biscuit-paginationview"), source ?? {}, [
			"page",
			"pageSize",
			"total",
			"theme"
		]);
	};
	var createAvatarView = (source) => {
		defineAvatarView();
		return options(document.createElement("biscuit-avatarview"), source ?? {}, [
			"src",
			"alt",
			"initials",
			"theme"
		]);
	};
	var createTableView = (source) => {
		defineTableView();
		return options(document.createElement("biscuit-tableview"), source ?? {}, [
			"columns",
			"rows",
			"dataSource",
			"updateCell",
			"page",
			"pageSize",
			"filter",
			"sortKey",
			"sortDirection",
			"rowHeight",
			"theme"
		]);
	};
	// src/charts/model.js
	var registry = /* @__PURE__ */ new Map();
	function registerChartType(name, definition) {
		if (!/^[a-z][a-z0-9-]*$/.test(String(name))) throw new TypeError("Chart type needs a lowercase name");
		if (!definition || typeof definition.domain !== "function" || typeof definition.geometry !== "function" || typeof definition.summary !== "function") throw new TypeError("Chart types need domain, geometry, and summary functions");
		registry.set(String(name), definition);
		return () => registry.delete(String(name));
	}
	var finite = (value) => value != null && value !== "" && Number.isFinite(Number(value));
	var numeric = (value) => value instanceof Date ? value.getTime() : typeof value === "string" && /^\d{4}-\d\d-\d\d/.test(value) ? Date.parse(value) : Number(value);
	var clamp = (value, min, max) => Math.max(min, Math.min(max, value));
	function inferAxis(values, requested = "auto") {
		if (requested !== "auto") return requested;
		const sample = values.find((value) => value != null && value !== "");
		if (sample instanceof Date || typeof sample === "string" && /^\d{4}-\d\d-\d\d/.test(sample)) return "time";
		return typeof sample === "number" ? "number" : "category";
	}
	function extent(values, includeZero = false) {
		let min = Infinity, max = -Infinity;
		for (const raw of values) {
			const value = numeric(raw);
			if (!Number.isFinite(value)) continue;
			min = Math.min(min, value);
			max = Math.max(max, value);
		}
		if (min === Infinity) return [0, 1];
		if (includeZero) {
			min = Math.min(0, min);
			max = Math.max(0, max);
		}
		if (min === max) {
			const pad = Math.abs(min) * .1 || 1;
			return [min - pad, max + pad];
		}
		return [min, max];
	}
	function scale(domain, range, type = "number") {
		if (type === "category") {
			const values = [...new Set(domain.map(String))];
			const index = new Map(values.map((value, i) => [value, i]));
			return Object.assign((value) => range[0] + (index.get(String(value)) + .5) / Math.max(1, values.length) * (range[1] - range[0]), {
				domain: values,
				band: (range[1] - range[0]) / Math.max(1, values.length),
				invert: (px) => values[clamp(Math.floor((px - range[0]) / (range[1] - range[0]) * values.length), 0, values.length - 1)]
			});
		}
		const [a, b] = domain.map(numeric);
		const span = b - a || 1;
		return Object.assign((value) => range[0] + (numeric(value) - a) / span * (range[1] - range[0]), {
			domain: [a, b],
			invert: (px) => a + (px - range[0]) / (range[1] - range[0]) * span
		});
	}
	function ticks(domain, type = "number", count = 5) {
		if (type === "category") {
			const step = Math.max(1, Math.ceil(domain.length / count));
			return domain.filter((_, i) => i % step === 0);
		}
		const [a, b] = domain;
		const raw = (b - a) / Math.max(1, count - 1);
		const magnitude = 10 ** Math.floor(Math.log10(raw || 1));
		const step = [
			1,
			2,
			5,
			10
		].map((n) => n * magnitude).find((n) => n >= raw) || magnitude * 10;
		const result = [];
		for (let v = Math.ceil(a / step) * step; v <= b + step * .001 && result.length < 20; v += step) result.push(Number(v.toPrecision(12)));
		return result.length ? result : [a, b];
	}
	function sampleMinMax(points, target) {
		if (points.length <= target) return points;
		const chunk = Math.ceil(points.length / Math.max(1, target / 2));
		const out = [];
		for (let i = 0; i < points.length; i += chunk) {
			let low = null, high = null;
			for (let j = i; j < Math.min(points.length, i + chunk); j++) {
				const p = points[j];
				if (!finite(p.y)) continue;
				if (!low || numeric(p.y) < numeric(low.y)) low = p;
				if (!high || numeric(p.y) > numeric(high.y)) high = p;
			}
			if (low) {
				if (low.index <= high.index) out.push(low, ...high === low ? [] : [high]);
				else out.push(high, low);
			}
		}
		return out;
	}
	function sampleStride(points, target) {
		if (points.length <= target) return points;
		const out = [];
		const step = points.length / target;
		for (let i = 0; i < target; i++) out.push(points[Math.floor(i * step)]);
		return out;
	}
	function aggregateHeat(points, xDomain, yDomain, maxCells = 6e3) {
		if (points.length <= maxCells) return points;
		const width = Math.max(1, Math.floor(Math.sqrt(maxCells)));
		const cells = /* @__PURE__ */ new Map();
		const [xa, xb] = xDomain, [ya, yb] = yDomain;
		for (const point of points) {
			const x = clamp(Math.floor((numeric(point.x) - xa) / (xb - xa || 1) * width), 0, width - 1), y = clamp(Math.floor((numeric(point.y) - ya) / (yb - ya || 1) * width), 0, width - 1);
			const key = x + "," + y;
			const cell = cells.get(key) || {
				...point,
				x: xa + (x + .5) / width * (xb - xa),
				y: ya + (y + .5) / width * (yb - ya),
				value: 0,
				count: 0
			};
			cell.value += numeric(point.value) || 0;
			cell.count++;
			cells.set(key, cell);
		}
		return [...cells.values()];
	}
	function normalize(data, series) {
		const records = Array.isArray(data) ? data : [];
		return (series || []).map((entry, index) => {
			const spec = {
				type: "line",
				x: "x",
				y: "y",
				name: entry.name || entry.y || `Series ${index + 1}`,
				...entry
			};
			const points = [];
			for (let i = 0; i < records.length; i++) {
				const row = records[i];
				if (row?.__series != null && row.__series !== (spec.sourceIndex ?? index)) continue;
				points.push({
					x: row?.[spec.x] ?? i,
					y: row?.[spec.y],
					value: row?.[spec.value || spec.y],
					label: row?.[spec.label || spec.x],
					row,
					index: i,
					series: index
				});
			}
			return {
				...spec,
				points
			};
		});
	}
	function stackSeries(series) {
		const positive = /* @__PURE__ */ new Map(), negative = /* @__PURE__ */ new Map();
		return series.map((spec) => ({
			...spec,
			points: spec.points.map((point) => {
				const value = numeric(point.y);
				if (!Number.isFinite(value)) return {
					...point,
					y0: NaN,
					y1: NaN
				};
				const key = String(point.x);
				const totals = value >= 0 ? positive : negative;
				const y0 = totals.get(key) || 0, y1 = y0 + value;
				totals.set(key, y1);
				return {
					...point,
					y0,
					y1
				};
			})
		}));
	}
	function prepare(series, width, height, { xType = "auto", yType = "number", stacked = false, viewport = null, bins = 24, maxMarks = 2e3 } = {}) {
		const all = series.flatMap((spec) => spec.points);
		const xKind = inferAxis(all.map((p) => p.x), xType), yKind = inferAxis(all.map((p) => p.y), yType);
		const xValues = all.map((p) => p.x).filter((v) => v != null);
		let xDomain = xKind === "category" ? [...new Set(xValues.map(String))] : extent(xValues);
		if (viewport && xKind !== "category") xDomain = [Math.max(xDomain[0], viewport[0]), Math.min(xDomain[1], viewport[1])];
		const stackedSeries = stacked ? stackSeries(series) : series;
		let yValues = stacked ? stackedSeries.flatMap((s) => s.points.map((p) => p.y1)) : all.map((p) => p.y);
		if (series.some((s) => s.type === "histogram")) {
			const [lo, hi] = extent(xValues);
			const count = clamp(Math.floor(bins) || 24, 1, maxMarks);
			const counts = Array(count).fill(0);
			for (const v of xValues) {
				const value = numeric(v);
				if (Number.isFinite(value)) counts[clamp(Math.floor((value - lo) / (hi - lo || 1) * count), 0, count - 1)]++;
			}
			yValues = counts;
		}
		const yDomain = extent(yValues, series.some((s) => [
			"bar",
			"area",
			"histogram"
		].includes(s.type)));
		const plot = {
			left: 52,
			top: 18,
			right: Math.max(53, width - 20),
			bottom: Math.max(40, height - 42)
		};
		const xScale = scale(xDomain, [plot.left, plot.right], xKind), yScale = scale(yDomain, [plot.bottom, plot.top], yKind);
		return {
			series: stackedSeries,
			xKind,
			yKind,
			xDomain,
			yDomain,
			xScale,
			yScale,
			plot,
			width,
			height
		};
	}
	var pointInPlot = (p, m) => m.xKind === "category" || numeric(p.x) >= m.xDomain[0] && numeric(p.x) <= m.xDomain[1];
	var pointMark = (point, spec, i, kind, shape, values) => ({
		kind,
		series: spec.sourceIndex ?? i,
		point,
		row: point.row,
		label: spec.name,
		shape,
		...values
	});
	function cartesianGeometry(model, { maxMarks = 2e3, stacked = false } = {}) {
		const marks = [];
		const { xScale: x, yScale: y, plot } = model;
		const bars = model.series.filter((s) => s.type === "bar").length;
		model.series.forEach((spec, i) => {
			const valid = spec.points.filter((p) => pointInPlot(p, model) && finite(p.y));
			const type = spec.type;
			if (type === "line" || type === "area" || type === "combo") {
				const segments = [];
				let current = [];
				for (const point of spec.points) {
					if (!pointInPlot(point, model)) continue;
					if (!finite(point.y)) {
						if (current.length) segments.push(current);
						current = [];
						continue;
					}
					current.push(point);
				}
				if (current.length) segments.push(current);
				const target = Math.max(100, Math.floor(maxMarks / model.series.length));
				for (const segment of segments) {
					const sampled = sampleMinMax(segment, Math.max(2, Math.round(target * segment.length / Math.max(1, valid.length))));
					const coords = sampled.map((p) => [x(p.x), y(stacked ? p.y1 : p.y)]);
					if (type === "area") {
						const base = sampled.map((p) => [x(p.x), y(stacked ? p.y0 : 0)]).reverse();
						marks.push(pointMark(sampled[0], spec, i, "area", "polygon", { points: [...coords, ...base] }));
					}
					marks.push(pointMark(sampled[0], spec, i, "line", "polyline", { points: coords }));
					if (valid.length <= 80) sampled.forEach((p) => marks.push(pointMark(p, spec, i, "point", "circle", {
						cx: x(p.x),
						cy: y(stacked ? p.y1 : p.y),
						r: 3.4
					})));
				}
			} else if (type === "scatter") sampleStride(valid, Math.max(100, Math.floor(maxMarks / model.series.length))).forEach((p) => marks.push(pointMark(p, spec, i, "point", "circle", {
				cx: x(p.x),
				cy: y(p.y),
				r: 4
			})));
			else if (type === "bar") {
				const sliced = sampleStride(valid, Math.max(100, Math.floor(maxMarks / model.series.length)));
				const band = model.xKind === "category" ? x.band : Math.max(4, (plot.right - plot.left) / Math.max(1, sliced.length));
				const width = Math.max(2, band * (stacked ? .72 : .72 / Math.max(1, bars)));
				const barIndex = model.series.slice(0, i).filter((s) => s.type === "bar").length;
				sliced.forEach((p) => {
					const cx = x(p.x) + (stacked ? 0 : (barIndex - (bars - 1) / 2) * width), v = y(stacked ? p.y1 : p.y), base = y(stacked ? p.y0 : 0);
					marks.push(pointMark(p, spec, i, "bar", "rect", {
						x: cx - width / 2,
						y: Math.min(v, base),
						width,
						height: Math.max(1, Math.abs(base - v))
					}));
				});
			}
		});
		return marks;
	}
	function histogramGeometry(model, { maxMarks = 2e3, bins = 24 } = {}) {
		const vals = (model.series[0]?.points || []).map((p) => numeric(p.x)).filter(Number.isFinite);
		if (!vals.length) return [];
		const [lo, hi] = extent(vals);
		const count = clamp(Math.floor(bins) || 24, 1, maxMarks);
		const buckets = Array.from({ length: count }, (_, i) => ({
			x: lo + (i + .5) / count * (hi - lo),
			y: 0,
			index: i
		}));
		for (const v of vals) buckets[clamp(Math.floor((v - lo) / (hi - lo || 1) * count), 0, count - 1)].y++;
		const max = Math.max(...buckets.map((b) => b.y), 1);
		const { plot } = model;
		const band = (plot.right - plot.left) / count;
		return buckets.map((p, i) => ({
			kind: "bar",
			shape: "rect",
			series: 0,
			point: p,
			label: "Count",
			x: plot.left + i * band + 1,
			y: plot.bottom - p.y / max * (plot.bottom - plot.top),
			width: Math.max(1, band - 2),
			height: p.y / max * (plot.bottom - plot.top)
		}));
	}
	function heatmapGeometry(model, { maxMarks = 6e3 } = {}) {
		const { xScale: x, yScale: y, plot } = model;
		const all = model.series.flatMap((s) => s.points).filter((p) => finite(p.value));
		const points = model.xKind === "category" ? sampleStride(all, maxMarks) : aggregateHeat(all, model.xDomain, model.yDomain, maxMarks);
		const max = Math.max(1, ...points.map((p) => Math.abs(numeric(p.value)) || 0));
		const bx = model.xKind === "category" ? x.band : (plot.right - plot.left) / Math.max(1, Math.sqrt(points.length));
		const by = model.yKind === "category" ? y.band : (plot.bottom - plot.top) / Math.max(1, Math.sqrt(points.length));
		return points.map((p) => ({
			kind: "heat",
			shape: "rect",
			series: p.series,
			point: p,
			label: "Value",
			x: x(p.x) - bx / 2,
			y: y(p.y) - by / 2,
			width: Math.max(1, bx),
			height: Math.max(1, by),
			intensity: Math.abs(numeric(p.value)) / max
		}));
	}
	function radialGeometry(model, { donut = false } = {}) {
		const points = model.series[0]?.points.filter((p) => numeric(p.y) > 0) || [];
		const total = points.reduce((a, p) => a + numeric(p.y), 0);
		if (!total) return [];
		const cx = model.width / 2, cy = model.height / 2, r = Math.min(model.width, model.height) * .37;
		let angle = -Math.PI / 2;
		return points.map((p) => {
			const start = angle, end = angle + numeric(p.y) / total * Math.PI * 2;
			angle = end;
			return {
				kind: "slice",
				shape: "arc",
				series: p.row?.__originalIndex ?? p.index,
				point: p,
				label: String(p.label),
				cx,
				cy,
				r,
				inner: donut ? r * .55 : 0,
				start,
				end
			};
		});
	}
	function treemapGeometry(model) {
		const points = (model.series[0]?.points || []).filter((p) => numeric(p.y) > 0).sort((a, b) => numeric(b.y) - numeric(a.y));
		const total = points.reduce((a, p) => a + numeric(p.y), 0);
		if (!total) return [];
		const { plot } = model;
		const marks = [];
		let x = plot.left, y = plot.top, w = plot.right - plot.left, h = plot.bottom - plot.top, remaining = total;
		points.forEach((p, i) => {
			const share = numeric(p.y) / remaining;
			const horizontal = w >= h;
			const size = (horizontal ? w : h) * share;
			marks.push({
				kind: "tree",
				shape: "rect",
				series: i,
				point: p,
				label: String(p.label),
				x,
				y,
				width: horizontal ? size : w,
				height: horizontal ? h : size
			});
			if (horizontal) {
				x += size;
				w -= size;
			} else {
				y += size;
				h -= size;
			}
			remaining -= numeric(p.y);
		});
		return marks;
	}
	function funnelGeometry(model) {
		const points = (model.series[0]?.points || []).filter((p) => numeric(p.y) >= 0);
		const max = Math.max(1, ...points.map((p) => numeric(p.y)));
		const { plot } = model;
		const band = (plot.bottom - plot.top) / Math.max(1, points.length), mid = (plot.left + plot.right) / 2, wide = (plot.right - plot.left) * .86;
		return points.map((p, i) => {
			const width = wide * numeric(p.y) / max, next = wide * numeric(points[i + 1]?.y ?? p.y) / max;
			const top = plot.top + i * band, bottom = top + band - 3;
			return {
				kind: "funnel",
				shape: "polygon",
				series: i,
				point: p,
				label: String(p.label),
				points: [
					[mid - width / 2, top],
					[mid + width / 2, top],
					[mid + next / 2, bottom],
					[mid - next / 2, bottom]
				]
			};
		});
	}
	var summary = (model) => {
		let values = 0;
		for (const spec of model.series) for (const point of spec.points) if (finite(point.y)) values++;
		return `${model.series.length} series with ${values} plotted values`;
	};
	for (const type of [
		"line",
		"area",
		"bar",
		"scatter",
		"combo"
	]) registerChartType(type, {
		domain: prepare,
		geometry: cartesianGeometry,
		summary
	});
	registerChartType("histogram", {
		domain: prepare,
		geometry: histogramGeometry,
		summary
	});
	registerChartType("heatmap", {
		domain: prepare,
		geometry: heatmapGeometry,
		summary
	});
	registerChartType("pie", {
		domain: prepare,
		geometry: (m, o) => radialGeometry(m, o),
		summary
	});
	registerChartType("donut", {
		domain: prepare,
		geometry: (m, o) => radialGeometry(m, {
			...o,
			donut: true
		}),
		summary
	});
	registerChartType("treemap", {
		domain: prepare,
		geometry: treemapGeometry,
		summary
	});
	registerChartType("funnel", {
		domain: prepare,
		geometry: funnelGeometry,
		summary
	});
	registerChartType("calendar", {
		domain: calendarDomain,
		geometry: calendarGeometry,
		summary: calendarSummary
	});
	function buildModel(data, series, options = {}) {
		const normalized = normalize(data, series);
		const first = normalized[0]?.type || "line";
		const type = options.type || first;
		const plugin = registry.get(type);
		if (!plugin) throw new TypeError(`Unknown chart type: ${type}`);
		const width = Math.max(280, Number(options.width) || 680), height = Math.max(220, Number(options.height) || 360);
		const model = plugin.domain(normalized, width, height, options);
		const marks = plugin.geometry(model, options);
		return {
			...model,
			marks,
			type,
			summary: plugin.summary(model, marks, options),
			records: data,
			sourceCount: data.length
		};
	}
	function hitTest(marks, x, y, tolerance = 12) {
		for (let i = marks.length - 1; i >= 0; i--) {
			const m = marks[i];
			if (m.shape === "circle" && Math.hypot(x - m.cx, y - m.cy) <= m.r + tolerance) return m;
			if (m.shape === "rect" && x >= m.x - tolerance && x <= m.x + m.width + tolerance && y >= m.y - tolerance && y <= m.y + m.height + tolerance) return m;
			if (m.shape === "polyline") for (let j = 1; j < m.points.length; j++) {
				const [ax, ay] = m.points[j - 1], [bx, by] = m.points[j];
				const t = clamp(((x - ax) * (bx - ax) + (y - ay) * (by - ay)) / ((bx - ax) ** 2 + (by - ay) ** 2 || 1), 0, 1);
				if (Math.hypot(x - (ax + (bx - ax) * t), y - (ay + (by - ay) * t)) < tolerance) return m;
			}
			if (m.shape === "arc") {
				const radius = Math.hypot(x - m.cx, y - m.cy);
				let a = Math.atan2(y - m.cy, x - m.cx);
				if (a < m.start) a += Math.PI * 2;
				if (radius <= m.r && radius >= m.inner && a >= m.start && a <= m.end) return m;
			}
		}
		return null;
	}
	// src/charts/render.js
	var esc = (value) => String(value ?? "").replace(/[&<>"']/g, (char) => ({
		"&": "&amp;",
		"<": "&lt;",
		">": "&gt;",
		"\"": "&quot;",
		"'": "&#39;"
	})[char]);
	var n = (value) => Number.isFinite(Number(value)) ? Number(value).toFixed(2) : "0";
	var palette = [
		"#7669d8",
		"#0d9993",
		"#ee9c52",
		"#d86586",
		"#527ebd",
		"#86a94d",
		"#9a6aba",
		"#ce6c52"
	];
	var darkPalette = [
		"#afa5ff",
		"#60d8cf",
		"#f7bd75",
		"#f49ab1",
		"#91b8ff",
		"#b5d674",
		"#d9a8fa",
		"#ffa98d"
	];
	var monoDark = [
		"#fff",
		"#ddd",
		"#bbb",
		"#999",
		"#f2f2f2",
		"#aaa",
		"#ccc",
		"#eee"
	];
	var monoLight = [
		"#20232c",
		"#555b68",
		"#808694",
		"#383e49",
		"#9ba0aa",
		"#626875",
		"#343b46",
		"#747b89"
	];
	function colors(theme = "light", mode = "color") {
		return mode === "monochrome" ? theme === "light" ? monoLight : monoDark : theme === "dark" || theme === "deep-dark" ? darkPalette : palette;
	}
	function format(value, kind) {
		if (kind === "time") return new Date(Number(value)).toLocaleDateString(void 0, {
			month: "short",
			day: "numeric"
		});
		if (typeof value === "number") return new Intl.NumberFormat(void 0, {
			maximumFractionDigits: 2,
			notation: Math.abs(value) >= 1e5 ? "compact" : "standard"
		}).format(value);
		return String(value ?? "");
	}
	function markSvg(mark, i, { palette: colorsList, gradient = "none", id = "chart", theme = "light", colorMode = "color", calendarColors = null, calendarColor = null, colorScale = "steps", calendarStyle = "calendar", calendarMax = 1 } = {}) {
		const color = colorsList[mark.series % colorsList.length], fill = colorMode === "monochrome" && ([
			"slice",
			"bar",
			"heat",
			"treemap",
			"funnel"
		].includes(mark.kind) || mark.series % 2) ? `url(#${id}-pattern-${mark.series % colorsList.length})` : gradient === "none" ? color : `url(#${id}-gradient-${mark.series % colorsList.length})`;
		const common = ` data-mark="${i}" data-series="${mark.series}"`;
		if (mark.kind === "calendar") {
			const selected = calendarValueColor(mark.point.y, calendarMax, calendarPalette(theme, colorMode, calendarStyle, calendarColors, calendarColor), colorScale);
			const dayFill = gradient !== "none" && mark.point.y > 0 ? `url(#${id}-calendar-fill-${selected.slice(1)})` : selected;
			if (calendarStyle === "calendar") return `<g${common}><rect x="${n(mark.x)}" y="${n(mark.y)}" width="${n(mark.width)}" height="${n(mark.height)}" rx="7" fill="${esc(theme === "deep-dark" ? "#000" : theme === "dark" ? "#242733" : "#fff")}" stroke="${esc(theme === "deep-dark" ? "#555" : theme === "dark" ? "#4f5260" : "#dfe3eb")}"/><rect x="${n(mark.x + 1)}" y="${n(mark.y + 1)}" width="5" height="${n(mark.height - 2)}" rx="2" fill="${esc(dayFill)}"/><text x="${n(mark.x + 13)}" y="${n(mark.y + 19)}" fill="${esc(theme === "light" ? "#23263a" : "#fff")}" font-size="12" font-weight="700">${Number(mark.date.slice(-2))}</text>${mark.point.y ? `<text x="${n(mark.x + 13)}" y="${n(mark.y + 36)}" fill="${esc(theme === "light" ? "#687082" : "#bbb")}" font-size="10">${esc(format(mark.point.y))}</text>` : ""}</g>`;
			return `<rect${common} x="${n(mark.x)}" y="${n(mark.y)}" width="${n(mark.width)}" height="${n(mark.height)}" rx="2" fill="${esc(dayFill)}" stroke="${theme === "deep-dark" ? "#555" : "none"}" stroke-width=".5"/>`;
		}
		if (mark.shape === "circle") return `<circle${common} cx="${n(mark.cx)}" cy="${n(mark.cy)}" r="${n(mark.r)}" fill="${esc(color)}" stroke="${theme === "deep-dark" ? "#000" : "white"}" stroke-width="1"/>`;
		if (mark.shape === "rect") return `<rect${common} x="${n(mark.x)}" y="${n(mark.y)}" width="${n(Math.max(0, mark.width))}" height="${n(Math.max(0, mark.height))}" rx="2" fill="${esc(fill)}" fill-opacity="${mark.kind === "heat" ? n(.14 + mark.intensity * .86) : "1"}" stroke="${theme === "deep-dark" ? "#fff" : "none"}" stroke-width="${theme === "deep-dark" ? ".7" : "0"}"/>`;
		if (mark.shape === "polyline" || mark.shape === "polygon") {
			const points = mark.points.map(([x, y]) => `${n(x)},${n(y)}`).join(" ");
			return `<${mark.shape}${common} points="${points}" fill="${mark.shape === "polyline" ? "none" : esc(fill)}" fill-opacity="${mark.kind === "area" ? gradient === "vivid" ? ".5" : ".22" : "1"}" stroke="${esc(color)}" stroke-width="${mark.shape === "polyline" ? "2.5" : ".8"}" stroke-dasharray="${colorMode === "monochrome" && mark.shape === "polyline" && mark.series % 2 ? "7 4" : ""}" stroke-linejoin="round"/>`;
		}
		if (mark.shape === "arc") {
			const { cx, cy, r, inner, start, end } = mark;
			const delta = end - start >= Math.PI * 2 - 1e-4 ? end - 1e-4 : end;
			const outerA = [cx + r * Math.cos(start), cy + r * Math.sin(start)], outerB = [cx + r * Math.cos(delta), cy + r * Math.sin(delta)];
			let d = `M ${n(outerA[0])} ${n(outerA[1])} A ${n(r)} ${n(r)} 0 ${delta - start > Math.PI ? 1 : 0} 1 ${n(outerB[0])} ${n(outerB[1])}`;
			if (inner) {
				const innerB = [cx + inner * Math.cos(delta), cy + inner * Math.sin(delta)], innerA = [cx + inner * Math.cos(start), cy + inner * Math.sin(start)];
				d += ` L ${n(innerB[0])} ${n(innerB[1])} A ${n(inner)} ${n(inner)} 0 ${delta - start > Math.PI ? 1 : 0} 0 ${n(innerA[0])} ${n(innerA[1])} Z`;
			} else d += ` L ${n(cx)} ${n(cy)} Z`;
			return `<path${common} d="${d}" fill="${esc(fill)}" stroke="${theme === "deep-dark" ? "#000" : theme === "dark" ? "#242733" : "#fff"}" stroke-width="2"/>`;
		}
		return "";
	}
	function axesSvg(model, { theme = "light", colorMode = "color", calendarColors = null, calendarColor = null, colorScale = "steps", id = "chart" } = {}) {
		if (model.type === "calendar") return calendarAxesSvg(model, {
			theme,
			colorMode,
			calendarColors,
			calendarColor,
			colorScale,
			id
		});
		if ([
			"pie",
			"donut",
			"treemap",
			"funnel"
		].includes(model.type)) return "";
		const { plot, xScale, yScale, xDomain, yDomain, xKind, yKind } = model;
		const ink = theme === "deep-dark" ? "#fff" : theme === "dark" ? "#b8bdcd" : "#687082";
		const grid = theme === "deep-dark" ? "#555" : theme === "dark" ? "#454a59" : "#e4e7ee";
		let out = "";
		for (const value of ticks(yDomain, yKind, 5)) {
			const y = yScale(value);
			out += `<line x1="${n(plot.left)}" x2="${n(plot.right)}" y1="${n(y)}" y2="${n(y)}" stroke="${grid}" stroke-dasharray="3 4"/><text x="${n(plot.left - 10)}" y="${n(y + 4)}" text-anchor="end" fill="${ink}" font-size="11">${esc(format(value, yKind))}</text>`;
		}
		for (const value of ticks(xDomain, xKind, 6)) {
			const x = xScale(value);
			out += `<text x="${n(x)}" y="${n(plot.bottom + 23)}" text-anchor="middle" fill="${ink}" font-size="11">${esc(format(value, xKind))}</text>`;
		}
		return out;
	}
	function gradientsSvg(id, colorsList, preset) {
		if (preset === "none") return "";
		const opacity = preset === "vivid" ? "1" : ".56";
		return `<defs>${colorsList.map((color, i) => `<linearGradient id="${id}-gradient-${i}" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stop-color="${esc(color)}" stop-opacity="${opacity}"/><stop offset="1" stop-color="${esc(color)}" stop-opacity="${preset === "vivid" ? ".34" : ".1"}"/></linearGradient>`).join("")}</defs>`;
	}
	function svgDocument(model, { theme = "light", gradient = "none", id = "chart", includeMarks = true, colorMode = "color", calendarColors = null, calendarColor = null, colorScale = "steps" } = {}) {
		const colorsList = colors(theme, colorMode);
		let defs = gradientsSvg(id, colorsList, gradient) + (colorMode === "monochrome" ? `<defs>${colorsList.map((color, i) => `<pattern id="${id}-pattern-${i}" patternUnits="userSpaceOnUse" width="8" height="8"><rect width="8" height="8" fill="${theme === "light" ? "#fff" : "#000"}"/>${i % 4 === 0 ? `<path d="M0 8L8 0" stroke="${theme === "light" ? "#111" : "#fff"}" stroke-width="3"/>` : i % 4 === 1 ? `<path d="M0 2H8 M0 6H8" stroke="${theme === "light" ? "#111" : "#fff"}" stroke-width="1.5"/>` : i % 4 === 2 ? `<circle cx="4" cy="4" r="2" fill="${theme === "light" ? "#111" : "#fff"}"/>` : `<path d="M0 0L8 8 M0 8L8 0" stroke="${theme === "light" ? "#111" : "#fff"}" stroke-width="1.5"/>`}</pattern>`).join("")}</defs>` : "");
		if (model.type === "calendar") {
			const palette = calendarPalette(theme, colorMode, model.calendar.style, calendarColors, calendarColor);
			if (colorScale === "continuous") defs += `<defs><linearGradient id="${id}-calendar-scale" x1="0" x2="1" y1="0" y2="0">${palette.slice(1).map((color, index) => `<stop offset="${Math.round(index * 100 / 3)}%" stop-color="${color}"/>`).join("")}</linearGradient></defs>`;
			if (gradient !== "none" && includeMarks) {
				const used = new Set(model.marks.filter((mark) => mark.point.y > 0).map((mark) => calendarValueColor(mark.point.y, model.calendar.max, palette, colorScale)));
				defs += `<defs>${[...used].map((color) => `<linearGradient id="${id}-calendar-fill-${color.slice(1)}" x1="0" x2="0" y1="0" y2="1"><stop offset="0" stop-color="${mixHex(color, "#ffffff", gradient === "vivid" ? .28 : .13)}"/><stop offset="1" stop-color="${mixHex(color, "#000000", gradient === "vivid" ? .22 : .1)}"/></linearGradient>`).join("")}</defs>`;
			}
		}
		const marks = includeMarks ? model.marks.map((mark, i) => markSvg(mark, i, {
			palette: colorsList,
			gradient,
			id,
			theme,
			colorMode,
			calendarColors,
			calendarColor,
			colorScale,
			calendarStyle: model.calendar?.style,
			calendarMax: model.calendar?.max
		})).join("") : "";
		const background = theme === "deep-dark" ? "#000" : theme === "dark" ? "#242733" : "#fff";
		return `<svg xmlns="http://www.w3.org/2000/svg" width="${model.width}" height="${model.height}" viewBox="0 0 ${model.width} ${model.height}" role="img"><rect width="100%" height="100%" fill="${background}"/>${defs}${axesSvg(model, {
			theme,
			colorMode,
			calendarColors,
			calendarColor,
			colorScale,
			id
		})}${marks}</svg>`;
	}
	function paintCanvas(canvas, model, { theme = "light", gradient = "none", colorMode = "color" } = {}) {
		const ratio = Math.max(1, globalThis.devicePixelRatio || 1);
		canvas.width = Math.round(model.width * ratio);
		canvas.height = Math.round(model.height * ratio);
		canvas.style.width = `${model.width}px`;
		canvas.style.height = `${model.height}px`;
		const ctx = canvas.getContext?.("2d");
		if (!ctx) return;
		ctx.scale(ratio, ratio);
		const list = colors(theme, colorMode);
		for (const mark of model.marks) {
			const color = list[mark.series % list.length];
			let fill = color;
			if (gradient !== "none" && [
				"rect",
				"polygon",
				"arc"
			].includes(mark.shape)) {
				const g = ctx.createLinearGradient(0, 0, 0, model.height);
				g.addColorStop(0, color);
				g.addColorStop(1, gradient === "vivid" ? `${color}70` : `${color}20`);
				fill = g;
			}
			ctx.beginPath();
			if (mark.shape === "circle") {
				ctx.arc(mark.cx, mark.cy, mark.r, 0, Math.PI * 2);
				ctx.fillStyle = color;
				ctx.fill();
			} else if (mark.shape === "rect") {
				ctx.rect(mark.x, mark.y, Math.max(0, mark.width), Math.max(0, mark.height));
				ctx.globalAlpha = mark.kind === "heat" ? clamp(.14 + mark.intensity * .86, 0, 1) : 1;
				ctx.fillStyle = fill;
				ctx.fill();
				ctx.globalAlpha = 1;
			} else if (mark.shape === "polyline" || mark.shape === "polygon") {
				mark.points.forEach(([x, y], i) => i ? ctx.lineTo(x, y) : ctx.moveTo(x, y));
				if (mark.shape === "polygon") {
					ctx.closePath();
					ctx.globalAlpha = mark.kind === "area" ? .25 : 1;
					ctx.fillStyle = fill;
					ctx.fill();
					ctx.globalAlpha = 1;
				}
				ctx.strokeStyle = color;
				ctx.lineWidth = mark.shape === "polyline" ? 2.5 : 1;
				ctx.setLineDash(colorMode === "monochrome" && mark.series % 2 ? [7, 4] : []);
				ctx.stroke();
				ctx.setLineDash([]);
			} else if (mark.shape === "arc") {
				ctx.arc(mark.cx, mark.cy, mark.r, mark.start, mark.end);
				if (mark.inner) ctx.arc(mark.cx, mark.cy, mark.inner, mark.end, mark.start, true);
				else ctx.lineTo(mark.cx, mark.cy);
				ctx.closePath();
				ctx.fillStyle = fill;
				ctx.fill();
				ctx.strokeStyle = theme === "deep-dark" ? "#000" : "#fff";
				ctx.lineWidth = 2;
				ctx.stroke();
			}
		}
	}
	function csv(records) {
		const keys = [...new Set(records.flatMap((row) => Object.keys(row || {})))];
		const quote = (value) => `"${String(value ?? "").replaceAll("\"", "\"\"")}"`;
		return [keys.map(quote).join(","), ...records.map((row) => keys.map((key) => quote(row?.[key])).join(","))].join("\r\n");
	}
	// src/charts/chart-view.js
	var TAG = "biscuit-chartview";
	var chartId = 0;
	var raf = (callback) => (globalThis.requestAnimationFrame || ((fn) => setTimeout(fn, 16)))(callback);
	var cancelRaf = (id) => (globalThis.cancelAnimationFrame || clearTimeout)(id);
	var escapeHtml = (value) => String(value ?? "").replace(/[&<>"']/g, (char) => ({
		"&": "&amp;",
		"<": "&lt;",
		">": "&gt;",
		"\"": "&quot;",
		"'": "&#39;"
	})[char]);
	var css = `
:host{display:block;min-width:0;color-scheme:inherit;--_surface:var(--biscuit-chart-surface,light-dark(#fff,#292c37));--_ink:var(--biscuit-chart-color,light-dark(#23263a,#f6f7fa));--_muted:var(--biscuit-chart-muted,light-dark(#697184,#b9bed0));--_line:var(--biscuit-chart-border,light-dark(#dfe3eb,#505667));--_accent:var(--biscuit-chart-accent,light-dark(#7669d8,#afa5ff));font:400 14px/1.4 system-ui,sans-serif;color:var(--_ink)}
:host([theme="light"]){color-scheme:light}:host([theme="dark"]){color-scheme:dark}:host([theme="deep-dark"]){color-scheme:dark;--_surface:#000;--_ink:#fff;--_muted:#c9c9c9;--_line:#fff;--_accent:#fff}
:host([spacing="compact"]){--_space:.75}:host([spacing="spacious"]){--_space:1.3}[hidden]{display:none!important}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip-path:inset(50%);white-space:nowrap}
*,*::before,*::after{box-sizing:border-box}.shell{background:var(--_surface);border:1px solid var(--_line);border-radius:16px;box-shadow:0 8px 28px light-dark(rgb(36 42 74 / .06),rgb(0 0 0 / .17));padding:calc(18px * var(--_space,1));overflow:hidden}
:host([theme="deep-dark"]) .shell{box-shadow:none}.head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px;flex-wrap:wrap}.eyebrow{margin:0 0 4px;color:var(--_accent);font-size:11px;font-weight:750;letter-spacing:.13em;text-transform:uppercase}.title{margin:0;font-size:20px;line-height:1.2;letter-spacing:-.025em}.summary{color:var(--_muted);margin:7px 0 0;font-size:13px}.actions{display:flex;gap:5px;align-items:center}.actions button,.paging button,.legend button{border:1px solid var(--_line);border-radius:8px;background:var(--_surface);color:var(--_ink);font:inherit;cursor:pointer}.actions button{padding:5px 9px}.actions button:disabled,.paging button:disabled{opacity:.45;cursor:default}.actions button:hover:not(:disabled),.paging button:hover:not(:disabled){border-color:var(--_accent)}button:focus-visible,.stage:focus-visible,summary:focus-visible{outline:2px solid var(--_accent);outline-offset:2px}.legend{display:flex;gap:6px 13px;flex-wrap:wrap;margin:16px 0 4px}.legend button{border-color:transparent;display:inline-flex;align-items:center;gap:7px;padding:4px 6px}.legend button[aria-pressed="false"]{opacity:.45;text-decoration:line-through}.swatch{width:15px;height:8px;border-radius:2px;border:1px solid var(--_line)}.stage{position:relative;min-height:220px;outline:none;touch-action:pan-y;cursor:crosshair}.stage canvas,.stage svg{position:absolute;inset:0;display:block;max-width:100%}.stage canvas{pointer-events:none}.stage svg{pointer-events:none}:host([type="calendar"]) .stage{overflow-x:auto;cursor:default}:host([type="calendar"]) .stage svg{max-width:none}.calendar-slots{position:absolute;inset:0;pointer-events:none}.calendar-slot{position:absolute;overflow:auto;pointer-events:auto}.calendar-slot slot{display:block;width:100%;height:100%}.calendar-slot slot::slotted(*){display:block;max-width:100%;max-height:100%;overflow:auto}.empty{display:grid;place-items:center;height:100%;min-height:220px;color:var(--_muted);text-align:center}.tooltip{position:absolute;z-index:3;max-width:210px;background:var(--_ink);color:var(--_surface);border:1px solid var(--_line);border-radius:8px;padding:7px 10px;font-size:12px;line-height:1.4;pointer-events:none;box-shadow:0 6px 18px rgb(0 0 0 / .18)}.tooltip[hidden],.brush[hidden]{display:none}.tooltip strong{display:block}.brush{position:absolute;z-index:2;top:15px;bottom:40px;background:color-mix(in srgb,var(--_accent) 18%,transparent);border:1px solid var(--_accent);pointer-events:none}.data{margin-top:14px;border-top:1px solid var(--_line);padding-top:10px}.data summary{color:var(--_muted);cursor:pointer}.table-wrap{overflow:auto;max-height:290px;margin-top:10px}table{width:100%;border-collapse:collapse;font-size:12px}th,td{padding:7px;text-align:left;border-bottom:1px solid var(--_line);white-space:nowrap}th{font-weight:700}.paging{display:flex;justify-content:flex-end;gap:8px;align-items:center;margin-top:9px;color:var(--_muted);font-size:12px}.paging button{padding:4px 8px}.status{color:var(--_muted);font-size:12px;min-height:1em;margin-top:6px}@media(max-width:520px){.shell{padding:12px;border-radius:12px}.title{font-size:18px}.actions{width:100%}.stage{min-height:240px}.legend{gap:2px 6px}}@media(prefers-reduced-motion:no-preference){.shell{transition:border-color .18s ease}.legend button{transition:opacity .16s ease}}`;
	function attrBool(host, name) {
		return host.hasAttribute(name);
	}
	function parseDeclarative(host) {
		const specs = [];
		const records = [];
		for (const [si, node] of [...host.querySelectorAll(":scope > biscuit-series")].entries()) {
			const spec = {
				type: node.getAttribute("type") || host.type,
				name: node.getAttribute("name") || `Series ${si + 1}`,
				x: node.getAttribute("x") || "x",
				y: node.getAttribute("y") || "y",
				value: node.getAttribute("value") || void 0,
				label: node.getAttribute("label") || void 0
			};
			specs.push(spec);
			for (const point of node.querySelectorAll(":scope > biscuit-point")) {
				const row = { __series: si };
				for (const attr of point.attributes) row[attr.name] = attr.value;
				row[spec.x] = point.getAttribute("x") ?? point.getAttribute("label") ?? records.length;
				row[spec.y] = point.getAttribute("y") ?? point.getAttribute("value") ?? 0;
				records.push(row);
			}
		}
		return {
			series: specs,
			data: records
		};
	}
	var BiscuitChartView = class extends HTMLElementBase {
		static observedAttributes = [
			"theme",
			"spacing",
			"type",
			"name",
			"gradient",
			"color-mode",
			"color-scale",
			"calendar-color",
			"stacked",
			"x-type",
			"y-type",
			"table-page-size",
			"year",
			"month",
			"week-start",
			"calendar-range",
			"calendar-style",
			"end-date",
			"day-height"
		];
		constructor() {
			super();
			this._id = `biscuit-chart-${++chartId}`;
			this._data = null;
			this._series = null;
			this._dataSource = null;
			this._view = null;
			this._hidden = /* @__PURE__ */ new Set();
			this._hiddenPoints = /* @__PURE__ */ new Set();
			this._tablePage = 0;
			this._activeMark = 0;
			this._request = 0;
			this._pending = [];
			this._flushTimer = null;
			this._renderFrame = null;
			this._observer = null;
			this._themeObserver = null;
			this._resize = null;
			this._model = null;
			this._drag = null;
			this._abort = null;
			this._linkedHandler = (event) => {
				if (event.detail?.source !== this && event.detail?.group === this.linkGroup) {
					this._linkedSelection = event.detail.selection;
					this._setStatus(`Linked selection: ${event.detail.selection?.label || "none"}`);
				}
			};
			if (this.attachShadow) {
				this._root = this.attachShadow({ mode: "open" });
				this._root.innerHTML = `<style>${css}</style><div class="shell" part="container"><div class="head"><div><h3 class="title" part="title"></h3><p class="summary" part="summary"></p></div><div class="actions" part="controls"><button class="reset" type="button" aria-label="Reset chart zoom" hidden>Reset zoom</button><button class="export" type="button" aria-label="Download chart as PNG">PNG ↓</button></div></div><div class="legend" part="legend"></div><div class="stage" part="plot" role="group" tabindex="0"><canvas aria-hidden="true"></canvas><div class="svg-slot" aria-hidden="true"></div><div class="calendar-slots" part="calendar-days"></div><div class="brush" hidden></div><div class="tooltip" role="tooltip" hidden></div><div class="empty" hidden></div></div><p class="status" role="status" aria-live="polite"></p><details class="data" part="data-table"><summary>View chart data</summary><div class="table-wrap"></div><div class="paging"><button class="prev" type="button">Previous</button><span class="page-label"></span><button class="next" type="button">Next</button></div></details></div>`;
				this._stage = this._root.querySelector(".stage");
				this._root.querySelector(".reset").addEventListener("click", () => this.resetZoom());
				this._root.querySelector(".export").addEventListener("click", async () => {
					try {
						const blob = await this.exportPNG();
						if (!blob) return;
						const link = document.createElement("a");
						link.href = URL.createObjectURL(blob);
						link.download = `${this.name || "chart"}.png`;
						link.click();
						setTimeout(() => URL.revokeObjectURL(link.href), 1e3);
					} catch (error) {
						this._emit("error", { error });
					}
				});
				this._root.querySelector(".prev").addEventListener("click", () => {
					this._tablePage = Math.max(0, this._tablePage - 1);
					this._renderTable();
				});
				this._root.querySelector(".next").addEventListener("click", () => {
					this._tablePage++;
					this._renderTable();
				});
				this._stage.addEventListener("pointermove", (e) => this._pointerMove(e));
				this._stage.addEventListener("pointerleave", () => this._hideTooltip());
				this._stage.addEventListener("click", (e) => this._selectAt(e));
				this._stage.addEventListener("keydown", (e) => this._key(e));
				this._stage.addEventListener("pointerdown", (e) => this._pointerDown(e));
				this._stage.addEventListener("pointerup", (e) => this._pointerUp(e));
				this._stage.addEventListener("wheel", (e) => this._wheel(e), { passive: false });
			}
		}
		connectedCallback() {
			this._observer = typeof MutationObserver !== "undefined" ? new MutationObserver(() => {
				if (this._data === null) this._scheduleRender();
			}) : null;
			this._observer?.observe(this, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: [
					"x",
					"y",
					"value",
					"label",
					"type",
					"name",
					"date"
				]
			});
			this._themeObserver = typeof MutationObserver !== "undefined" ? new MutationObserver(() => this._scheduleRender()) : null;
			for (let node = this.parentNode || this.getRootNode?.().host; node; node = node.parentNode || node.host) if (node.nodeType === 1) this._themeObserver?.observe(node, {
				attributes: true,
				attributeFilter: [
					"theme",
					"data-page-theme",
					"class",
					"style"
				]
			});
			this._resize = typeof ResizeObserver !== "undefined" ? new ResizeObserver(() => this._scheduleRender()) : null;
			this._resize?.observe(this);
			globalThis.addEventListener?.("biscuit-chart-link", this._linkedHandler);
			this._scheduleRender();
		}
		disconnectedCallback() {
			this._observer?.disconnect();
			this._themeObserver?.disconnect();
			this._resize?.disconnect();
			this._abort?.abort();
			globalThis.removeEventListener?.("biscuit-chart-link", this._linkedHandler);
			if (this._renderFrame) cancelRaf(this._renderFrame);
			if (this._flushTimer) clearTimeout(this._flushTimer);
		}
		attributeChangedCallback() {
			this._scheduleRender();
		}
		get name() {
			return this.getAttribute("name") || "Chart";
		}
		set name(v) {
			this.setAttribute("name", String(v));
		}
		get type() {
			return this.getAttribute("type") || "line";
		}
		set type(v) {
			this.setAttribute("type", String(v));
		}
		get gradient() {
			return this.getAttribute("gradient") || "none";
		}
		set gradient(v) {
			this.setAttribute("gradient", [
				"none",
				"subtle",
				"vivid"
			].includes(v) ? v : "none");
		}
		get colorMode() {
			return this.getAttribute("color-mode") || "color";
		}
		set colorMode(v) {
			this.setAttribute("color-mode", v === "monochrome" ? "monochrome" : "color");
		}
		get colorScale() {
			return this.getAttribute("color-scale") === "continuous" ? "continuous" : "steps";
		}
		set colorScale(v) {
			this.setAttribute("color-scale", v === "continuous" ? "continuous" : "steps");
		}
		get calendarColor() {
			return this.getAttribute("calendar-color") || null;
		}
		set calendarColor(v) {
			if (v == null) this.removeAttribute("calendar-color");
			else this.setAttribute("calendar-color", String(v));
		}
		get stacked() {
			return attrBool(this, "stacked");
		}
		set stacked(v) {
			this.toggleAttribute("stacked", Boolean(v));
		}
		get xType() {
			return this.getAttribute("x-type") || "auto";
		}
		set xType(v) {
			this.setAttribute("x-type", String(v));
		}
		get yType() {
			return this.getAttribute("y-type") || "number";
		}
		set yType(v) {
			this.setAttribute("y-type", String(v));
		}
		get linkGroup() {
			return this.getAttribute("link-group") || "";
		}
		set linkGroup(v) {
			this.setAttribute("link-group", String(v));
		}
		get highFrequency() {
			return attrBool(this, "high-frequency");
		}
		set highFrequency(v) {
			this.toggleAttribute("high-frequency", Boolean(v));
		}
		get maxRows() {
			return Math.max(1, Number(this.getAttribute("max-rows")) || 1e5);
		}
		set maxRows(v) {
			this.setAttribute("max-rows", String(v));
		}
		get year() {
			return Number(this.getAttribute("year")) || null;
		}
		set year(v) {
			if (v == null) this.removeAttribute("year");
			else this.setAttribute("year", String(v));
		}
		get month() {
			return Number(this.getAttribute("month")) || null;
		}
		set month(v) {
			if (v == null) this.removeAttribute("month");
			else this.setAttribute("month", String(v));
		}
		get weekStart() {
			return Number(this.getAttribute("week-start")) || 0;
		}
		set weekStart(v) {
			this.setAttribute("week-start", String(v));
		}
		get calendarRange() {
			return this.getAttribute("calendar-range") || "year";
		}
		set calendarRange(v) {
			this.setAttribute("calendar-range", v === "rolling" ? "rolling" : "year");
		}
		get calendarStyle() {
			return this.getAttribute("calendar-style") || "calendar";
		}
		set calendarStyle(v) {
			this.setAttribute("calendar-style", v === "activity" ? "activity" : "calendar");
		}
		get endDate() {
			return this.getAttribute("end-date") || null;
		}
		set endDate(v) {
			if (v == null) this.removeAttribute("end-date");
			else this.setAttribute("end-date", String(v));
		}
		get dayHeight() {
			return Number(this.getAttribute("day-height")) || 92;
		}
		set dayHeight(v) {
			this.setAttribute("day-height", String(v));
		}
		get calendarColors() {
			return this._calendarColors ?? null;
		}
		set calendarColors(v) {
			this._calendarColors = Array.isArray(v) ? v.slice() : null;
			this._scheduleRender();
		}
		setDayContent(date, content) {
			const key = String(date);
			let day = [...this.querySelectorAll(":scope > biscuit-calendar-day")].find((node) => node.getAttribute("date") === key);
			if (!day) {
				day = document.createElement("biscuit-calendar-day");
				day.setAttribute("date", key);
				this.append(day);
			}
			day.replaceChildren(...(Array.isArray(content) ? content : [content]).filter(Boolean).map((item) => typeof item === "string" ? document.createTextNode(item) : item));
			this._scheduleRender();
			return day;
		}
		get data() {
			return this._data ?? parseDeclarative(this).data;
		}
		set data(v) {
			this._data = Array.isArray(v) ? v.slice() : [];
			this._tablePage = 0;
			this._scheduleRender();
		}
		get series() {
			return this._series ?? parseDeclarative(this).series;
		}
		set series(v) {
			this._series = Array.isArray(v) ? v.map((s) => ({ ...s })) : [];
			this._hidden.clear();
			this._scheduleRender();
		}
		get dataSource() {
			return this._dataSource;
		}
		set dataSource(v) {
			if (v != null && typeof v !== "function") throw new TypeError("dataSource must be a function");
			this._abort?.abort();
			this._request++;
			this._dataSource = v;
			this.refresh();
		}
		get viewport() {
			return this._view ? [...this._view] : null;
		}
		get model() {
			return this._model;
		}
		appendData(batch) {
			const rows = Array.isArray(batch) ? batch : [batch];
			this._pending.push(...rows);
			if (this.highFrequency) {
				if (!this._flushTimer) this._flushTimer = raf(() => {
					this._flushTimer = null;
					this._flush();
				});
			} else if (!this._flushTimer) this._flushTimer = setTimeout(() => {
				this._flushTimer = null;
				this._flush();
			}, 100);
		}
		_flush() {
			if (!this._pending.length) return;
			this._data = [...this._data ?? parseDeclarative(this).data, ...this._pending].slice(-this.maxRows);
			this._pending = [];
			this._scheduleRender();
			this._emit("load", {
				rows: this._data,
				total: this._data.length,
				incremental: true
			});
		}
		async refresh() {
			if (!this._dataSource) {
				this._scheduleRender();
				return;
			}
			this._abort?.abort();
			const controller = new AbortController();
			this._abort = controller;
			const request = ++this._request;
			try {
				const result = await this._dataSource({
					signal: controller.signal,
					viewport: this.viewport
				});
				if (request !== this._request || controller.signal.aborted) return;
				this._data = (Array.isArray(result) ? result : result?.rows ?? []).slice(-this.maxRows);
				this._tablePage = 0;
				this._emit("load", {
					rows: this._data,
					total: result?.total ?? this._data.length
				});
				this._scheduleRender();
			} catch (error) {
				if (controller.signal.aborted || request !== this._request) return;
				this._setStatus("Could not load chart data.");
				this._emit("error", { error });
			}
		}
		resetZoom() {
			if (!this._view) return;
			this._view = null;
			this._emit("viewportchange", { viewport: null });
			this._emit("change", { viewport: null });
			this.refresh();
			this._scheduleRender();
		}
		exportSVG({ scale = 2 } = {}) {
			if (!this._model) return "";
			return svgDocument(this._model, {
				theme: this._effectiveTheme(),
				gradient: this.gradient,
				id: this._id,
				colorMode: this.colorMode,
				colorScale: this.colorScale,
				calendarColor: this.calendarColor,
				calendarColors: this.calendarColors
			}).replace(`width="${this._model.width}" height="${this._model.height}"`, `width="${Math.round(this._model.width * scale)}" height="${Math.round(this._model.height * scale)}"`);
		}
		async exportPNG({ scale = 2 } = {}) {
			const svg = this.exportSVG();
			if (!svg) return null;
			const blob = new Blob([svg], { type: "image/svg+xml" });
			const url = URL.createObjectURL(blob);
			try {
				const image = new Image();
				await new Promise((resolve, reject) => {
					image.onload = resolve;
					image.onerror = reject;
					image.src = url;
				});
				const canvas = document.createElement("canvas");
				canvas.width = this._model.width * scale;
				canvas.height = this._model.height * scale;
				canvas.getContext("2d").drawImage(image, 0, 0, canvas.width, canvas.height);
				return await new Promise((resolve) => canvas.toBlob(resolve, "image/png"));
			} finally {
				URL.revokeObjectURL(url);
			}
		}
		exportCSV() {
			return csv(this.data);
		}
		_emit(type, detail) {
			event(this, type, detail);
		}
		_setStatus(message) {
			const node = this._root?.querySelector(".status");
			if (node) node.textContent = message;
		}
		_effectiveTheme() {
			let node = this;
			while (node) {
				const theme = node.getAttribute?.("theme") || node.getAttribute?.("data-page-theme");
				if ([
					"light",
					"dark",
					"deep-dark"
				].includes(theme)) return theme;
				node = node.parentNode || node.host;
			}
			const scheme = globalThis.getComputedStyle?.(this).colorScheme;
			if (scheme === "light" || scheme === "dark") return scheme;
			return globalThis.matchMedia?.("(prefers-color-scheme: dark)").matches ? "dark" : "light";
		}
		_scheduleRender() {
			if (!this.isConnected || !this._root || this._renderFrame) return;
			this._renderFrame = raf(() => {
				this._renderFrame = null;
				this._render();
			});
		}
		_render() {
			try {
				const parsed = parseDeclarative(this);
				const series = (this._series ?? parsed.series).map((spec, i) => ({
					...spec,
					sourceIndex: i
				})).filter((_, i) => !this._hidden.has(i));
				let data = this._data ?? parsed.data;
				if (parsed.series.length && this._data === null) data = parsed.data.filter((r) => !this._hidden.has(r.__series));
				if ([
					"pie",
					"donut",
					"treemap",
					"funnel"
				].includes(this.type)) data = data.map((row, i) => ({
					...row,
					__originalIndex: i
				})).filter((_, i) => !this._hiddenPoints.has(i));
				if (!series.length && data.length) series.push({
					type: this.type,
					x: "x",
					y: "y",
					name: this.name
				});
				const width = Math.max(280, Math.floor(this.getBoundingClientRect?.().width || this.clientWidth || 680) - (this.type === "calendar" ? 42 : 36));
				const height = Math.max(220, Number(this.getAttribute("height")) || (this.type === "calendar" && this.calendarStyle === "activity" ? 210 : 340));
				const maxMarks = data.length > 2e3 ? Math.min(12e3, Math.max(2e3, Math.floor(width * 3))) : 2e3;
				const model = buildModel(data, series, {
					type: this.type,
					width,
					height,
					maxMarks,
					stacked: this.stacked,
					xType: this.xType,
					yType: this.yType,
					viewport: this._view,
					bins: Number(this.getAttribute("bins")) || 24,
					year: this.year,
					month: this.month,
					weekStart: this.weekStart,
					calendarRange: this.calendarRange,
					calendarStyle: this.calendarStyle,
					endDate: this.endDate,
					dayHeight: this.dayHeight
				});
				this._model = model;
				this._root.querySelector(".title").textContent = this.name;
				this._root.querySelector(".summary").textContent = model.sourceCount ? `${model.summary}. ${model.sourceCount.toLocaleString()} source rows.` : "No data yet. Add records or <biscuit-point> children.";
				this._root.querySelector(".reset").hidden = !this._view;
				this._root.querySelector(".empty").hidden = Boolean(model.marks.length);
				this._root.querySelector(".empty").textContent = "No chart data to display";
				this._stage.style.height = `${model.height + (this.type === "calendar" ? 18 : 0)}px`;
				this._stage.setAttribute("aria-label", this.type === "calendar" ? `${this.name}. ${model.summary}. Use arrow keys to explore days and Enter to select.` : `${this.name}. ${model.summary}. Use arrow keys to explore marks, Enter to select. Hold Control while scrolling to zoom; Shift-drag to brush, Alt-drag to pan.`);
				const theme = this._effectiveTheme();
				const canvas = this._root.querySelector("canvas");
				const svgSlot = this._root.querySelector(".svg-slot");
				const canvasMode = model.type !== "calendar" && model.marks.length > 2e3;
				canvas.hidden = !canvasMode;
				if (canvasMode) {
					paintCanvas(canvas, model, {
						theme,
						gradient: this.gradient,
						colorMode: this.colorMode,
						calendarColors: this.calendarColors
					});
					svgSlot.innerHTML = svgDocument(model, {
						theme,
						gradient: this.gradient,
						id: this._id,
						includeMarks: false,
						colorMode: this.colorMode,
						colorScale: this.colorScale,
						calendarColor: this.calendarColor,
						calendarColors: this.calendarColors
					});
				} else svgSlot.innerHTML = svgDocument(model, {
					theme,
					gradient: this.gradient,
					id: this._id,
					colorMode: this.colorMode,
					colorScale: this.colorScale,
					calendarColor: this.calendarColor,
					calendarColors: this.calendarColors
				});
				svgSlot.style.width = `${model.width}px`;
				svgSlot.style.height = `${model.height}px`;
				this._renderCalendarSlots();
				this._renderLegend();
				this._renderTable();
			} catch (error) {
				this._setStatus(error.message);
				this._emit("error", { error });
			}
		}
		_renderCalendarSlots() {
			const container = this._root.querySelector(".calendar-slots");
			container.replaceChildren();
			if (this.type !== "calendar" || this.calendarStyle !== "calendar") return;
			container.style.width = `${this._model.width}px`;
			container.style.height = `${this._model.height}px`;
			const marks = new Map(this._model.marks.map((mark) => [mark.date, mark]));
			for (const day of this.querySelectorAll(":scope > biscuit-calendar-day[date]")) {
				const date = day.getAttribute("date"), mark = marks.get(date);
				if (!mark) continue;
				const slotName = `day-${date}`;
				if (day.slot !== slotName) day.slot = slotName;
				const wrapper = document.createElement("div");
				wrapper.className = "calendar-slot";
				wrapper.style.left = `${mark.x + 10}px`;
				wrapper.style.top = `${mark.y + 39}px`;
				wrapper.style.width = `${Math.max(0, mark.width - 18)}px`;
				wrapper.style.height = `${Math.max(0, mark.height - 44)}px`;
				const slot = document.createElement("slot");
				slot.name = slotName;
				wrapper.append(slot);
				container.append(wrapper);
			}
		}
		_renderLegend() {
			const node = this._root.querySelector(".legend");
			node.replaceChildren();
			if (this.type === "calendar") {
				node.hidden = true;
				return;
			}
			const specs = this._series ?? parseDeclarative(this).series;
			const radial = [
				"pie",
				"donut",
				"treemap",
				"funnel"
			].includes(this.type);
			const items = radial ? this.data : specs;
			if (!items.length) {
				node.hidden = true;
				return;
			}
			node.hidden = false;
			const palette = colors(this._effectiveTheme(), this.colorMode);
			items.forEach((item, i) => {
				const button = document.createElement("button");
				button.type = "button";
				button.setAttribute("aria-pressed", String(!(radial ? this._hiddenPoints : this._hidden).has(i)));
				const swatch = document.createElement("span");
				swatch.className = "swatch";
				swatch.style.background = palette[i % palette.length];
				if (this.colorMode === "monochrome") {
					const ink = this._effectiveTheme() === "light" ? "#111" : "#fff", base = this._effectiveTheme() === "light" ? "#fff" : "#000";
					swatch.style.backgroundColor = base;
					swatch.style.backgroundImage = [
						`repeating-linear-gradient(135deg,${ink} 0 2px,${base} 2px 6px)`,
						`repeating-linear-gradient(0deg,${ink} 0 2px,${base} 2px 5px)`,
						`radial-gradient(${ink} 30%,${base} 32%)`,
						`repeating-linear-gradient(45deg,${ink} 0 1px,${base} 1px 4px),repeating-linear-gradient(135deg,${ink} 0 1px,transparent 1px 4px)`
					][i % 4];
					swatch.style.backgroundSize = i % 4 === 2 ? "6px 6px" : "auto";
				}
				button.append(swatch, document.createTextNode(radial ? String(item[specs[0]?.label || specs[0]?.x || "x"] ?? `Item ${i + 1}`) : item.name || item.y || `Series ${i + 1}`));
				button.addEventListener("click", () => {
					const hidden = radial ? this._hiddenPoints : this._hidden;
					if (hidden.has(i)) hidden.delete(i);
					else hidden.add(i);
					this._emit("legendchange", {
						series: i,
						visible: !hidden.has(i)
					});
					this._emit("change", {
						series: i,
						visible: !hidden.has(i)
					});
					this._scheduleRender();
				});
				node.append(button);
			});
		}
		_renderTable() {
			const rows = this.type === "calendar" ? this._model?.calendar?.days.map((day) => ({
				date: day.date,
				count: day.value
			})) ?? [] : this.data, wrap = this._root.querySelector(".table-wrap");
			const size = Math.max(1, Number(this.getAttribute("table-page-size")) || 10), pages = Math.max(1, Math.ceil(rows.length / size));
			this._tablePage = clamp(this._tablePage, 0, pages - 1);
			const slice = rows.slice(this._tablePage * size, (this._tablePage + 1) * size);
			const keys = [...new Set(slice.flatMap((row) => Object.keys(row || {}).filter((key) => !key.startsWith("__"))))];
			wrap.innerHTML = `<table><caption class="sr-only">${escapeHtml(this.name)} data</caption><thead><tr>${keys.map((k) => `<th scope="col">${escapeHtml(k)}</th>`).join("")}</tr></thead><tbody>${slice.map((row) => `<tr>${keys.map((k) => `<td>${escapeHtml(row?.[k])}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
			this._root.querySelector(".page-label").textContent = `Page ${this._tablePage + 1} of ${pages}`;
			this._root.querySelector(".prev").disabled = this._tablePage === 0;
			this._root.querySelector(".next").disabled = this._tablePage >= pages - 1;
		}
		_coords(e) {
			const rect = this._stage.getBoundingClientRect();
			return this.type === "calendar" ? {
				x: e.clientX - rect.left + this._stage.scrollLeft,
				y: e.clientY - rect.top + this._stage.scrollTop
			} : {
				x: (e.clientX - rect.left) * (this._model?.width / (rect.width || this._model?.width) || 1),
				y: (e.clientY - rect.top) * (this._model?.height / (rect.height || this._model?.height) || 1)
			};
		}
		_pointerMove(e) {
			if (!this._model) return;
			const { x, y } = this._coords(e);
			if (this._drag) {
				this._drag.end = x;
				if (this._drag.mode === "brush") {
					const brush = this._root.querySelector(".brush");
					brush.hidden = false;
					brush.style.left = `${Math.min(this._drag.start, x)}px`;
					brush.style.width = `${Math.abs(x - this._drag.start)}px`;
				}
				this._emit("input", {
					viewport: this._view,
					gesture: this._drag.mode
				});
				return;
			}
			const mark = hitTest(this._model.marks, x, y, this.type === "calendar" ? 0 : 7);
			if (!mark) {
				this._hideTooltip();
				return;
			}
			const tooltip = this._root.querySelector(".tooltip");
			tooltip.hidden = false;
			tooltip.innerHTML = `<strong>${escapeHtml(mark.label)}</strong>${escapeHtml(mark.point?.x ?? "")}: ${escapeHtml(format(mark.point?.y ?? mark.point?.value ?? "", this._model.yKind))}`;
			tooltip.style.left = `${clamp(x + 12, 0, this._model.width - 160)}px`;
			tooltip.style.top = `${clamp(y - 30, 0, this._model.height - 50)}px`;
		}
		_hideTooltip() {
			const tip = this._root?.querySelector(".tooltip");
			if (tip) tip.hidden = true;
		}
		_selectAt(e) {
			if (this._drag || !this._model) return;
			const { x, y } = this._coords(e);
			const mark = hitTest(this._model.marks, x, y, this.type === "calendar" ? 0 : 7);
			if (mark) this._select(mark);
		}
		_select(mark) {
			const selection = {
				series: mark.series,
				label: mark.label,
				point: mark.point,
				row: mark.row
			};
			this._emit("selectionchange", { selection });
			this._emit("change", { selection });
			this._setStatus(`Selected ${mark.label}, ${mark.point?.x ?? ""}: ${mark.point?.y ?? mark.point?.value ?? ""}`);
			if (this.linkGroup) globalThis.dispatchEvent?.(new CustomEvent("biscuit-chart-link", { detail: {
				group: this.linkGroup,
				selection,
				source: this
			} }));
		}
		_key(e) {
			if (!this._model?.marks.length) return;
			const list = this._model.marks;
			if ([
				"ArrowRight",
				"ArrowDown",
				"ArrowLeft",
				"ArrowUp",
				"Home",
				"End"
			].includes(e.key)) {
				e.preventDefault();
				this._activeMark = e.key === "Home" ? 0 : e.key === "End" ? list.length - 1 : clamp(this._activeMark + (this.type === "calendar" ? e.key === "ArrowRight" ? this.calendarStyle === "activity" ? 7 : 1 : e.key === "ArrowLeft" ? this.calendarStyle === "activity" ? -7 : -1 : e.key === "ArrowDown" ? this.calendarStyle === "activity" ? 1 : 7 : this.calendarStyle === "activity" ? -1 : -7 : e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : -1), 0, list.length - 1);
				const m = list[this._activeMark];
				this._setStatus(`Mark ${this._activeMark + 1} of ${list.length}: ${m.label}, ${m.point?.x ?? ""}, ${m.point?.y ?? m.point?.value ?? ""}. Press Enter to select.`);
			} else if (e.key === "Enter" || e.key === " ") {
				e.preventDefault();
				this._select(list[this._activeMark]);
			} else if (e.key === "Escape") this.resetZoom();
		}
		_pointerDown(e) {
			if (!this._model || !(e.shiftKey || e.altKey) || !["number", "time"].includes(this._model.xKind)) return;
			e.preventDefault();
			this._stage.setPointerCapture?.(e.pointerId);
			this._drag = {
				mode: e.shiftKey ? "brush" : "pan",
				start: this._coords(e).x,
				end: this._coords(e).x,
				viewport: this._view ?? this._model.xDomain
			};
		}
		_pointerUp(e) {
			if (!this._drag || !this._model) return;
			const drag = this._drag;
			this._drag = null;
			this._root.querySelector(".brush").hidden = true;
			const end = this._coords(e).x;
			const { xScale, plot } = this._model;
			if (drag.mode === "brush" && Math.abs(end - drag.start) > 8) this._view = [xScale.invert(clamp(Math.min(drag.start, end), plot.left, plot.right)), xScale.invert(clamp(Math.max(drag.start, end), plot.left, plot.right))];
			else if (drag.mode === "pan" && this._view) {
				const delta = xScale.invert(drag.start) - xScale.invert(end);
				this._view = [drag.viewport[0] + delta, drag.viewport[1] + delta];
			} else return;
			this._emit("viewportchange", { viewport: this.viewport });
			this._emit("change", { viewport: this.viewport });
			this.refresh();
			this._scheduleRender();
		}
		_wheel(e) {
			if (!e.ctrlKey && !e.metaKey || !this._model || !["number", "time"].includes(this._model.xKind)) return;
			e.preventDefault();
			const domain = this._view ?? this._model.xDomain;
			const mid = this._model.xScale.invert(this._coords(e).x);
			const factor = e.deltaY > 0 ? 1.15 : .85;
			this._view = [mid + (domain[0] - mid) * factor, mid + (domain[1] - mid) * factor];
			this._emit("input", {
				viewport: this.viewport,
				gesture: "zoom"
			});
			this._emit("viewportchange", { viewport: this.viewport });
			this._emit("change", { viewport: this.viewport });
			this.refresh();
			this._scheduleRender();
		}
	};
	themeProperty(BiscuitChartView);
	function defineChartView() {
		return define(TAG, BiscuitChartView);
	}
	function createChartView(options = {}) {
		defineChartView();
		const element = document.createElement(TAG);
		for (const key of [
			"theme",
			"spacing",
			"type",
			"name",
			"gradient",
			"colorMode",
			"colorScale",
			"calendarColor",
			"stacked",
			"xType",
			"yType",
			"linkGroup",
			"highFrequency",
			"maxRows",
			"year",
			"month",
			"weekStart",
			"calendarRange",
			"calendarStyle",
			"endDate",
			"dayHeight",
			"calendarColors"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		if (options.height) element.setAttribute("height", String(options.height));
		if (options.series) element.series = options.series;
		if (options.data) element.data = options.data;
		if (options.dataSource) element.dataSource = options.dataSource;
		return element;
	}
	// src/charts/calendar-charts.js
	var BiscuitCalendarChart = class extends BiscuitChartView {
		connectedCallback() {
			if (!this.hasAttribute("type")) this.type = "calendar";
			if (!this.hasAttribute("calendar-style")) this.calendarStyle = "calendar";
			if (!this.hasAttribute("name")) this.name = "Calendar chart";
			super.connectedCallback();
		}
		_render() {
			if (!this._series && !this.querySelector(":scope > biscuit-series")) this._series = [{
				type: "calendar",
				x: "date",
				y: "count",
				name: "Daily value"
			}];
			super._render();
		}
	};
	var BiscuitActivityChart = class extends BiscuitCalendarChart {
		connectedCallback() {
			if (!this.hasAttribute("calendar-style")) this.calendarStyle = "activity";
			if (!this.hasAttribute("calendar-range") && !this.hasAttribute("year")) this.calendarRange = "rolling";
			if (!this.hasAttribute("name")) this.name = "Activity chart";
			super.connectedCallback();
		}
		_select(mark) {
			super._select(mark);
			this._emit("dayselect", {
				date: mark.date,
				count: mark.point.y,
				row: mark.row
			});
		}
	};
	function defineCalendarChart() {
		return define("biscuit-calendarchart", BiscuitCalendarChart);
	}
	function defineActivityChart() {
		return define("biscuit-activity-chart", BiscuitActivityChart);
	}
	function configure(element, options) {
		for (const key of [
			"theme",
			"spacing",
			"name",
			"gradient",
			"colorMode",
			"colorScale",
			"calendarColor",
			"year",
			"month",
			"weekStart",
			"calendarRange",
			"endDate",
			"dayHeight",
			"calendarColors",
			"highFrequency",
			"maxRows"
		]) if (Object.hasOwn(options, key)) element[key] = options[key];
		if (options.height) element.setAttribute("height", String(options.height));
		if (options.series) element.series = options.series;
		if (options.data) element.data = options.data;
		if (options.dataSource) element.dataSource = options.dataSource;
		return element;
	}
	function createCalendarChart(options = {}) {
		defineCalendarChart();
		return configure(document.createElement("biscuit-calendarchart"), options);
	}
	function createActivityChart(options = {}) {
		defineActivityChart();
		return configure(document.createElement("biscuit-activity-chart"), options);
	}
	// src/charts.js
	var charts_exports = /* @__PURE__ */ __exportAll({
		BiscuitActivityChart: () => BiscuitActivityChart,
		BiscuitCalendarChart: () => BiscuitCalendarChart,
		BiscuitChartView: () => BiscuitChartView,
		createActivityChart: () => createActivityChart,
		createCalendarChart: () => createCalendarChart,
		createChartView: () => createChartView,
		defineActivityChart: () => defineActivityChart,
		defineCalendarChart: () => defineCalendarChart,
		defineChartView: () => defineChartView,
		defineCharts: () => defineCharts,
		registerChartType: () => registerChartType
	});
	function defineCharts() {
		defineChartView();
		defineCalendarChart();
		defineActivityChart();
		return defineChartView();
	}
	if (globalThis.customElements && globalThis.HTMLElement) defineCharts();
	// src/index.js
	var VERSION = "0.1.0";
	if (globalThis.customElements && globalThis.HTMLElement) {
		defineCore();
		defineLayout();
		defineMedia();
		defineCode();
		defineForms();
		defineOverlays();
		defineData();
		defineNavigation();
		defineCharts();
	}
	var Biscuit = Object.freeze({
		version: VERSION,
		TextView: BiscuitTextView,
		CardView: BiscuitCardView,
		DropdownView: BiscuitDropdownView,
		LoadingIndicator: BiscuitLoadingIndicator,
		PageLoaderView: BiscuitPageLoaderView,
		BadgeView: BiscuitBadgeView,
		ButtonView: BiscuitButtonView,
		CalendarView: BiscuitCalendarView,
		DatePickerView: BiscuitDatePickerView,
		SplitView: BiscuitSplitView,
		TabLayout: BiscuitTabLayout,
		NavBarView: BiscuitNavBarView,
		FooterView: BiscuitFooterView,
		SidebarView: BiscuitSidebarView,
		IconGridView: BiscuitIconGridView,
		FileBrowserView: BiscuitFileBrowserView,
		ImageView: BiscuitImageView,
		CarouselView: BiscuitCarouselView,
		CopyButtonView: BiscuitCopyButtonView,
		SuggestionView: BiscuitSuggestionView,
		CodeExampleView: BiscuitCodeExampleView,
		CodeView: BiscuitCodeView,
		InputView: BiscuitInputView,
		ComboBoxView: BiscuitComboBoxView,
		FieldView: BiscuitFieldView,
		CheckboxView: BiscuitCheckboxView,
		RadioGroupView: BiscuitRadioGroupView,
		SwitchView: BiscuitSwitchView,
		SliderView: BiscuitSliderView,
		FormWizardView: BiscuitFormWizardView,
		DialogView: BiscuitDialogView,
		PopoverView: BiscuitPopoverView,
		TooltipView: BiscuitTooltipView,
		MenuView: BiscuitMenuView,
		ToastView: BiscuitToastView,
		ProgressView: BiscuitProgressView,
		SkeletonView: BiscuitSkeletonView,
		PaginationView: BiscuitPaginationView,
		AvatarView: BiscuitAvatarView,
		TableView: BiscuitTableView,
		AccordionView: BiscuitAccordionView,
		TreeView: BiscuitTreeView,
		BreadcrumbView: BiscuitBreadcrumbView,
		StepperView: BiscuitStepperView,
		ChartView: BiscuitChartView,
		CalendarChart: BiscuitCalendarChart,
		ActivityChart: BiscuitActivityChart,
		...core_exports,
		...layout_exports,
		...media_exports,
		...code_exports,
		...forms_exports,
		...overlays_exports,
		...data_exports,
		...navigation_exports,
		...charts_exports,
		registerChartType,
		createIcon,
		getIconSvg,
		iconNames
	});
	// src/browser.js
	globalThis.Biscuit = Biscuit;
})();
