// Progressive enhancements. Everything also works without JavaScript, just with full page loads.
(() => {
  'use strict';

  // Ask before destructive actions: <form data-confirm="Delete this?">
  document.addEventListener('submit', (event) => {
    const message = event.target.dataset.confirm;
    if (message && !window.confirm(message)) event.preventDefault();
  });

  // ---------- Quantity steppers: update in place instead of reloading the page ----------

  const latestRequest = new WeakMap();
  const queues = new WeakMap();

  document.addEventListener('submit', async (event) => {
    const form = event.target;
    if (event.defaultPrevented || !form.matches('form[data-quantity]')) return;
    event.preventDefault();

    const input = form.querySelector('input[name="set"]');
    const before = currentQuantity(form);
    const body = new URLSearchParams(new FormData(form));
    const button = event.submitter;
    let expected;
    if (button && button.name === 'delta') {
      body.set('delta', button.value);
      body.delete('set');
      expected = Math.max(0, before + Number(button.value));
    } else {
      if (!input || input.value === '') return;
      expected = Math.max(0, Math.floor(Number(input.value)) || 0);
    }

    // Show the new number right away. Requests for one item go out one at a time, so with
    // quick repeated taps the answer to the last tap is also the final count.
    const request = (latestRequest.get(form) || 0) + 1;
    latestRequest.set(form, request);
    showQuantity(form, expected);
    const send = async () => {
      try {
        const response = await fetch(form.action, { method: 'POST', body, headers: { Accept: 'application/json' } });
        if (!response.ok) throw new Error(`HTTP ${response.status}`);
        const result = await response.json();
        if (latestRequest.get(form) === request) showQuantity(form, result.quantity, result.low);
      } catch (error) {
        if (latestRequest.get(form) !== request) return;
        showQuantity(form, before);
        window.alert('Could not update the quantity. Check the connection and try again.');
      }
    };
    const queued = (queues.get(form) || Promise.resolve()).then(send);
    queues.set(form, queued);
    await queued;
  });

  // Typing an exact count saves it when the field loses focus (phone keypads have no Enter key).
  document.addEventListener('change', (event) => {
    const input = event.target;
    if (!input.matches('form[data-quantity] input[name="set"]') || input.value === '') return;
    if (input.form.requestSubmit) {
      input.form.requestSubmit();
    } else {
      input.form.dispatchEvent(new Event('submit', { bubbles: true, cancelable: true }));
    }
  });

  function currentQuantity(form) {
    const input = form.querySelector('input[name="set"]');
    const display = form.querySelector('[data-qty]');
    return Number(input ? input.defaultValue : display.textContent) || 0;
  }

  function showQuantity(form, quantity, low) {
    const input = form.querySelector('input[name="set"]');
    const display = form.querySelector('[data-qty]');
    if (input) {
      input.defaultValue = String(quantity);
      if (document.activeElement !== input) input.value = String(quantity);
    }
    if (display) display.textContent = String(quantity);
    if (low === undefined) low = form.dataset.min !== undefined && quantity <= Number(form.dataset.min);
    (form.closest('.item-row, .facts') || form).classList.toggle('low', low);
  }

  // ---------- Live search: re-run the search as you type ----------

  const searchForm = document.querySelector('form[data-live-search]');
  if (searchForm) {
    const searchInput = searchForm.querySelector('input[name="q"]');
    let timer;
    let controller;
    searchInput.addEventListener('input', () => {
      clearTimeout(timer);
      timer = setTimeout(async () => {
        const url = `/search?${new URLSearchParams(new FormData(searchForm))}`;
        if (controller) controller.abort();
        controller = new AbortController();
        try {
          const response = await fetch(url, { signal: controller.signal });
          if (!response.ok) return;
          const page = new DOMParser().parseFromString(await response.text(), 'text/html');
          const fresh = page.getElementById('results');
          const current = document.getElementById('results');
          if (!fresh || !current) return;
          current.replaceWith(fresh);
          document.title = page.title;
          history.replaceState(null, '', url);
        } catch (error) {
          if (error.name !== 'AbortError') console.error(error);
        }
      }, 200);
    });
  }

  // ---------- Photos: shrink camera photos before uploading, and preview them ----------

  for (const input of document.querySelectorAll('input[data-photo-input]')) {
    // The Choose photo button stands in for the browser's own file field, which can't be styled.
    const choose = input.form.querySelector('[data-choose-photo]');
    if (choose) {
      input.classList.add('sr-only');
      choose.hidden = false;
    }
    input.addEventListener('change', () => {
      input.photoReady = preparePhoto(input).finally(() => input.dispatchEvent(new Event('photo-ready')));
    });
  }

  async function preparePhoto(input) {
    const thumbInput = input.form.querySelector('input[data-photo-thumb]');
    const preview = input.form.querySelector('[data-photo-preview]');
    const file = input.files[0];
    thumbInput.value = '';
    preview.hidden = true;
    if (!file) return;
    try {
      const image = await decodeImage(file);
      const [large, small] = await Promise.all([toJpeg(image, 1600, 0.85), toJpeg(image, 320, 0.8)]);
      setFile(input, new File([large], 'photo.jpg', { type: 'image/jpeg' }));
      setFile(thumbInput, new File([small], 'thumb.jpg', { type: 'image/jpeg' }));
      preview.src = URL.createObjectURL(small);
      preview.hidden = false;
    } catch (error) {
      // Unusual format or older browser: the original file gets uploaded as it is.
      console.warn('Could not resize the photo', error);
    }
  }

  async function decodeImage(file) {
    if ('createImageBitmap' in window) {
      try {
        return await createImageBitmap(file, { imageOrientation: 'from-image' });
      } catch (error) {
        // Fall back to an <img> below.
      }
    }
    const url = URL.createObjectURL(file);
    try {
      const image = new Image();
      image.src = url;
      await image.decode();
      return image;
    } finally {
      URL.revokeObjectURL(url);
    }
  }

  function toJpeg(image, maxSize, quality) {
    const width = image.videoWidth || image.naturalWidth || image.width;
    const height = image.videoHeight || image.naturalHeight || image.height;
    const scale = Math.min(1, maxSize / Math.max(width, height));
    const canvas = document.createElement('canvas');
    canvas.width = Math.max(1, Math.round(width * scale));
    canvas.height = Math.max(1, Math.round(height * scale));
    const context = canvas.getContext('2d');
    context.fillStyle = '#fff'; // JPEG has no transparency
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.drawImage(image, 0, 0, canvas.width, canvas.height);
    return new Promise((resolve, reject) => {
      canvas.toBlob((blob) => (blob ? resolve(blob) : reject(new Error('Could not encode JPEG'))), 'image/jpeg', quality);
    });
  }

  function setFile(input, file) {
    const transfer = new DataTransfer();
    transfer.items.add(file);
    input.files = transfer.files;
  }

  // ---------- Take photo: the camera app on phones, a live camera view on computers ----------

  for (const button of document.querySelectorAll('[data-take-photo]')) {
    const photoInput = button.form.querySelector('input[data-photo-input]');
    if (matchMedia('(pointer: coarse)').matches) {
      // A phone's own camera app takes better photos than a page can, and also works over plain http://.
      const captureInput = button.form.querySelector('input[data-photo-capture]');
      button.hidden = false;
      button.addEventListener('click', () => captureInput.click());
      captureInput.addEventListener('change', () => {
        if (captureInput.files.length) usePhoto(photoInput, captureInput.files[0]);
        captureInput.value = '';
      });
    } else {
      const dialog = button.form.querySelector('dialog[data-camera]');
      hasCamera().then((found) => {
        if (!found || !dialog.showModal) return;
        button.hidden = false;
        button.addEventListener('click', liveCamera(dialog, photoInput));
      });
    }
  }

  /** Browsers only offer the camera to pages on https:// or localhost. */
  async function hasCamera() {
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return false;
    try {
      const devices = await navigator.mediaDevices.enumerateDevices();
      return devices.some((device) => device.kind === 'videoinput');
    } catch (error) {
      return false;
    }
  }

  /** Hands the photo to the photo input, as if it had been chosen there. */
  function usePhoto(photoInput, file) {
    setFile(photoInput, file);
    photoInput.dispatchEvent(new Event('change'));
  }

  /** Sets up the camera dialog and returns the function that opens it. */
  function liveCamera(dialog, photoInput) {
    const video = dialog.querySelector('video');
    const status = dialog.querySelector('[data-camera-status]');
    const shoot = dialog.querySelector('[data-camera-shoot]');
    const switcher = dialog.querySelector('[data-camera-switch]');
    let stream = null;
    let cameras = [];
    let cameraId; // the camera last switched to, used again next time
    let attempt = 0; // a newer start, or closing, makes a slower getUserMedia answer stale

    const show = (message) => {
      status.hidden = !message;
      status.textContent = message;
    };
    const stop = () => {
      if (stream) for (const track of stream.getTracks()) track.stop();
      stream = null;
      video.srcObject = null;
      shoot.disabled = true;
    };

    async function start() {
      const current = ++attempt;
      stop();
      switcher.disabled = true;
      show('Starting the camera…');
      try {
        const choice = cameraId ? { deviceId: { exact: cameraId } } : { facingMode: 'environment' };
        const next = await navigator.mediaDevices.getUserMedia({ video: { ...choice, width: { ideal: 1920 }, height: { ideal: 1080 } } });
        if (current !== attempt) {
          for (const track of next.getTracks()) track.stop();
          return;
        }
        stream = next;
        video.srcObject = stream;
        await video.play();
        if (current !== attempt) return;
        show('');
        shoot.disabled = false;
        shoot.focus();
        // Until the browser may use the camera, it lists at most one.
        cameras = (await navigator.mediaDevices.enumerateDevices()).filter((device) => device.kind === 'videoinput');
        switcher.hidden = cameras.length < 2;
        switcher.disabled = false;
      } catch (error) {
        if (current !== attempt) return;
        if (cameraId && error.name === 'OverconstrainedError') {
          cameraId = undefined; // unplugged since, so take any camera
          start();
        } else {
          show(cameraError(error));
        }
      }
    }

    switcher.addEventListener('click', () => {
      const currentId = stream && stream.getVideoTracks()[0].getSettings().deviceId;
      const index = cameras.findIndex((camera) => camera.deviceId === currentId);
      cameraId = cameras[(index + 1) % cameras.length].deviceId;
      start();
    });
    shoot.addEventListener('click', async () => {
      shoot.disabled = true;
      try {
        const photo = await toJpeg(video, Infinity, 0.92);
        dialog.close();
        usePhoto(photoInput, new File([photo], 'camera.jpg', { type: 'image/jpeg' }));
      } catch (error) {
        show(`Couldn't take the photo: ${error.message}.`);
        shoot.disabled = false;
      }
    });
    dialog.querySelector('[data-camera-close]').addEventListener('click', () => dialog.close());
    dialog.addEventListener('close', () => {
      attempt += 1;
      stop();
    });

    return () => {
      dialog.showModal();
      start();
    };
  }

  function cameraError(error) {
    switch (error.name) {
      case 'NotAllowedError':
        return `The browser isn't allowed to use the camera. Allow it in the site settings, or choose a photo instead.`;
      case 'NotFoundError':
        return 'No camera found.';
      case 'NotReadableError':
        return `The camera is in use by another app, or couldn't be started.`;
      default:
        return `Couldn't start the camera: ${error.message}.`;
    }
  }

  // ---------- Fill in item details with Claude, from the photo, the typed name, or both ----------

  for (const button of document.querySelectorAll('[data-ai-fill]')) {
    const form = button.form;
    const photoInput = form.querySelector('input[data-photo-input]');
    const nameInput = form.elements.namedItem('name');
    const update = () => {
      button.disabled = !(photoInput.files.length || savedPhoto(button) || nameInput.value.trim());
    };
    button.closest('.ai-fill').hidden = false;
    update();
    button.addEventListener('click', () => fillWithAi(button));
    nameInput.addEventListener('input', update);
    form.elements.namedItem('remove_photo')?.addEventListener('change', update);
    photoInput.addEventListener('photo-ready', () => {
      const fromAi = photoInput.fromAi;
      photoInput.fromAi = false;
      form.querySelector('.photo-tile').classList.toggle('ai-filled', Boolean(fromAi));
      update();
      // A new item's photo is read straight away, unless a name was typed first or Claude found it.
      if (photoInput.files.length && !nameInput.value.trim() && !fromAi) fillWithAi(button);
    });
  }

  /** The id of the item whose saved photo Claude may look at, unless it's being removed. */
  function savedPhoto(button) {
    const remove = button.form.elements.namedItem('remove_photo');
    return remove && remove.checked ? '' : button.dataset.aiFill;
  }

  /** Shows [message] in an AI status line; [kind] is ai-busy, ai-ok, ai-warn or ai-error. */
  function showStatus(status, message, kind = '') {
    status.hidden = false;
    status.className = `ai-status ${kind}`;
    status.textContent = message;
  }

  async function fillWithAi(button) {
    const form = button.form;
    const show = (message, kind) => showStatus(form.querySelector('[data-ai-status]'), message, kind);
    button.disabled = true;
    try {
      const photoInput = form.querySelector('input[data-photo-input]');
      await photoInput.photoReady;
      const body = new FormData();
      const name = form.elements.namedItem('name').value.trim();
      if (name) body.append('name', name);
      if (photoInput.files.length) body.append('photo', photoInput.files[0]);
      else if (savedPhoto(button)) body.append('item', savedPhoto(button));
      const hasPhoto = body.has('photo') || body.has('item');
      show(`Asking Claude about ${hasPhoto && name ? 'the photo and name' : hasPhoto ? 'the photo' : 'the name'}…`, 'ai-busy');
      const response = await fetch('/ai/fill', { method: 'POST', body, headers: { Accept: 'application/json' } });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || `the server answered ${response.status}`);

      const found = result.name || result.quantity || result.notes || (result.tags || []).length || (result.properties || []).length;
      const filled = fillItemForm(form, result);
      if (filled.length) {
        show(`Filled in ${filled.join(', ')}. Please check before saving.`, 'ai-ok');
      } else if (found) {
        show('Nothing new to fill in: the form already has what Claude found.', 'ai-ok');
      } else {
        show(`No details found. ${hasPhoto ? 'A sharp, close photo works best.' : 'A more specific name may help.'}`, 'ai-warn');
      }
    } catch (error) {
      show(`Couldn't fill in: ${error.message.replace(/\.$/, '')}.`, 'ai-error');
    } finally {
      button.disabled = false;
    }
  }

  // ---------- Find photo: one from the web, for the typed name ----------

  for (const button of document.querySelectorAll('[data-find-photo]')) {
    const nameInput = button.form.elements.namedItem('name');
    const update = () => {
      button.disabled = !nameInput.value.trim();
    };
    button.hidden = false;
    update();
    nameInput.addEventListener('input', update);
    button.addEventListener('click', async () => {
      await findPhoto(button);
      update();
    });
  }

  async function findPhoto(button) {
    const form = button.form;
    const show = (message, kind) => showStatus(form.querySelector('[data-photo-status]'), message, kind);
    const name = form.elements.namedItem('name').value.trim();
    button.disabled = true;
    try {
      show(`Looking on the web for a photo of ${name}…`, 'ai-busy');
      const body = new FormData();
      body.append('name', name);
      const response = await fetch('/ai/photo', { method: 'POST', body, headers: { Accept: 'application/json' } });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || `the server answered ${response.status}`);
      if (!result.photo) {
        show(result.note, 'ai-warn');
        return;
      }
      const photoInput = form.querySelector('input[data-photo-input]');
      const bytes = Uint8Array.from(atob(result.photo.data), (char) => char.charCodeAt(0));
      photoInput.fromAi = true;
      usePhoto(photoInput, new File([bytes], 'photo', { type: result.photo.type }));
      const remove = form.elements.namedItem('remove_photo');
      if (remove) remove.checked = false;
      show(`Found a photo on ${new URL(result.photo.source).hostname.replace(/^www\./, '')}. Please check it's the right item before saving.`, 'ai-ok');
    } catch (error) {
      show(`Couldn't find a photo: ${error.message.replace(/\.$/, '')}.`, 'ai-error');
    }
  }

  /** Puts the suggestions into the form without overwriting anything already typed, and highlights what changed. */
  function fillItemForm(form, result) {
    const filled = [];
    const field = (name) => form.elements.namedItem(name);
    if (result.name && !field('name').value.trim()) {
      setFilled(field('name'), result.name);
      filled.push('the name');
    }
    if (result.quantity && ['', '1'].includes(field('quantity').value.trim())) {
      setFilled(field('quantity'), result.quantity);
      filled.push('the quantity');
    }
    const tags = tagList(field('tags'));
    const newTags = (result.tags || []).filter((tag) => !tags.some((existing) => sameTag(existing, tag)));
    if (newTags.length) {
      setFilled(field('tags'), [...tags, ...newTags].join(', '));
      syncTagChips(field('tags'));
      filled.push(count(newTags.length, 'tag'));
    }
    const rows = form.querySelector('[data-property-rows]');
    const known = new Set([...rows.querySelectorAll('[name="property_name"]')].map((input) => input.value.trim().toLowerCase()));
    let added = 0;
    for (const property of result.properties || []) {
      if (known.has(property.name.toLowerCase())) continue;
      let row = [...rows.children].find((candidate) => [...candidate.querySelectorAll('input')].every((input) => !input.value));
      if (!row) {
        row = rows.lastElementChild.cloneNode(true);
        rows.append(row);
      }
      setFilled(row.querySelector('[name="property_name"]'), property.name);
      setFilled(row.querySelector('[name="property_value"]'), property.value);
      added += 1;
    }
    if (added) {
      filled.push(count(added, 'property', 'properties'));
      rows.append(blankRow(rows)); // keep an empty row for typing more
    }
    if (result.notes && !field('notes').value.trim()) {
      setFilled(field('notes'), result.notes);
      filled.push('notes');
    }
    return filled;
  }

  /** Fills in a field and marks it, until it's edited, as filled in by Claude. */
  function setFilled(input, value) {
    input.value = value;
    input.classList.add('ai-filled');
    input.addEventListener('input', () => input.classList.remove('ai-filled'), { once: true });
  }

  /** An empty copy of the last property row. */
  function blankRow(rows) {
    const row = rows.lastElementChild.cloneNode(true);
    for (const input of row.querySelectorAll('input')) {
      input.value = '';
      input.classList.remove('ai-filled');
    }
    return row;
  }

  const count = (n, word, plural = `${word}s`) => `${n} ${n === 1 ? word : plural}`;

  // ---------- Tag suggestions: pressing one adds its tag to the field, or takes it out again ----------

  const tagList = (input) => input.value.split(',').map((tag) => tag.trim()).filter(Boolean);
  const sameTag = (a, b) => a.toLowerCase() === b.toLowerCase();

  function syncTagChips(input) {
    const tags = tagList(input);
    for (const chip of input.closest('.field').querySelectorAll('[data-toggle-tag]')) {
      chip.setAttribute('aria-pressed', String(tags.some((tag) => sameTag(tag, chip.dataset.toggleTag))));
    }
  }

  for (const input of document.querySelectorAll('input[name="tags"]')) {
    syncTagChips(input);
    input.addEventListener('input', () => syncTagChips(input));
  }

  // ---------- Name suggestions: an app-styled menu in place of the browser's own <datalist> dropdown ----------

  const suggestMenu = document.createElement('ul');
  suggestMenu.className = 'suggest-menu';
  suggestMenu.id = 'suggest-menu';
  suggestMenu.setAttribute('role', 'listbox');
  suggestMenu.hidden = true;
  document.body.append(suggestMenu);
  let suggestInput = null;
  let suggestActive = -1;

  // Copied property rows keep data-suggest, so they get the menu too.
  for (const input of document.querySelectorAll('input[list]')) {
    input.dataset.suggest = input.getAttribute('list');
    input.removeAttribute('list');
  }

  function suggestionsFor(input, all) {
    const typed = input.value.trim().toLowerCase();
    const options = [...(document.getElementById(input.dataset.suggest)?.options || [])].map((option) => option.value);
    if (!typed) return all ? options.slice(0, 8) : [];
    const starts = [];
    const contains = [];
    for (const value of options) {
      const lower = value.toLowerCase();
      if (lower === typed) continue;
      if (lower.startsWith(typed)) starts.push(value);
      else if (lower.includes(typed)) contains.push(value);
    }
    return [...starts, ...contains].slice(0, 8);
  }

  function openSuggestions(input, all = false) {
    const values = suggestionsFor(input, all);
    if (values.length === 0) return closeSuggestions();
    const typed = input.value.trim();
    suggestMenu.replaceChildren(...values.map((value, i) => {
      const option = document.createElement('li');
      option.id = `suggest-option-${i}`;
      option.setAttribute('role', 'option');
      option.dataset.value = value;
      const at = typed ? value.toLowerCase().indexOf(typed.toLowerCase()) : -1;
      if (at < 0) {
        option.textContent = value;
      } else {
        const mark = document.createElement('mark');
        mark.textContent = value.slice(at, at + typed.length);
        option.append(value.slice(0, at), mark, value.slice(at + typed.length));
      }
      return option;
    }));
    suggestInput = input;
    suggestActive = -1;
    input.setAttribute('aria-expanded', 'true');
    input.setAttribute('aria-controls', suggestMenu.id);
    input.removeAttribute('aria-activedescendant');
    suggestMenu.hidden = false;
    placeSuggestions();
  }

  function closeSuggestions() {
    if (!suggestInput) return;
    suggestInput.setAttribute('aria-expanded', 'false');
    suggestInput.removeAttribute('aria-activedescendant');
    suggestMenu.hidden = true;
    suggestInput = null;
  }

  function placeSuggestions() {
    if (!suggestInput) return;
    const box = suggestInput.getBoundingClientRect();
    const width = Math.max(box.width, Math.min(240, document.documentElement.clientWidth - 16));
    const left = Math.min(box.left, document.documentElement.clientWidth - width - 8);
    suggestMenu.style.width = `${width}px`;
    suggestMenu.style.left = `${Math.max(8, left) + window.scrollX}px`;
    suggestMenu.style.top = `${box.bottom + 4 + window.scrollY}px`;
  }

  function highlightSuggestion(index) {
    const options = suggestMenu.children;
    suggestActive = (index + options.length) % options.length;
    for (const [i, option] of [...options].entries()) option.setAttribute('aria-selected', String(i === suggestActive));
    const active = options[suggestActive];
    suggestInput.setAttribute('aria-activedescendant', active.id);
    active.scrollIntoView({ block: 'nearest' });
  }

  function chooseSuggestion(option) {
    const input = suggestInput;
    closeSuggestions();
    input.value = option.dataset.value;
    input.dispatchEvent(new Event('input', { bubbles: true }));
  }

  for (const input of document.querySelectorAll('input[data-suggest]')) {
    input.setAttribute('role', 'combobox');
    input.setAttribute('aria-autocomplete', 'list');
    input.setAttribute('aria-expanded', 'false');
  }

  document.addEventListener('input', (event) => {
    if (event.isTrusted && event.target.matches('input[data-suggest]')) openSuggestions(event.target);
  });
  document.addEventListener('focusout', (event) => {
    if (event.target === suggestInput) closeSuggestions();
  });
  document.addEventListener('keydown', (event) => {
    const input = event.target;
    if (!input.matches || !input.matches('input[data-suggest]')) return;
    const open = suggestInput === input;
    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      if (!open) openSuggestions(input, true);
      if (suggestInput !== input) return;
      event.preventDefault();
      const down = event.key === 'ArrowDown';
      highlightSuggestion(suggestActive < 0 ? (down ? 0 : -1) : suggestActive + (down ? 1 : -1));
    } else if (event.key === 'Enter' && open && suggestActive >= 0) {
      event.preventDefault();
      chooseSuggestion(suggestMenu.children[suggestActive]);
    } else if (event.key === 'Escape' && open) {
      event.preventDefault();
      closeSuggestions();
    }
  });
  // Keeps focus in the field, so picking a suggestion doesn't close the menu first.
  suggestMenu.addEventListener('pointerdown', (event) => event.preventDefault());
  suggestMenu.addEventListener('click', (event) => {
    const option = event.target.closest('[role="option"]');
    if (option) chooseSuggestion(option);
  });
  window.addEventListener('resize', placeSuggestions);

  // ---------- Press / to search ----------

  document.addEventListener('keydown', (event) => {
    if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey || event.defaultPrevented) return;
    if (event.target.closest && event.target.closest('input, textarea, select, [contenteditable]')) return;
    const search = document.getElementById('q');
    if (!search) return;
    event.preventDefault();
    search.focus();
    search.select();
  });

  // ---------- Small click helpers ----------

  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-add-property], [data-remove-property], [data-toggle-tag], [data-remove-label], [data-print]');
    if (!target) return;

    if (target.matches('[data-add-property]')) {
      const rows = target.form.querySelector('[data-property-rows]');
      const row = blankRow(rows);
      rows.append(row);
      row.querySelector('input').focus();
    } else if (target.matches('[data-remove-property]')) {
      const row = target.closest('.property-row');
      if (row.parentElement.children.length > 1) {
        row.remove();
      } else {
        for (const field of row.querySelectorAll('input')) field.value = '';
      }
    } else if (target.matches('[data-toggle-tag]')) {
      const input = target.closest('.field').querySelector('input[name="tags"]');
      const tag = target.dataset.toggleTag;
      const tags = tagList(input);
      const kept = tags.filter((existing) => !sameTag(existing, tag));
      input.value = (kept.length < tags.length ? kept : [...tags, tag]).join(', ');
      syncTagChips(input);
    } else if (target.matches('[data-remove-label]')) {
      target.closest('.label').remove();
    } else if (target.matches('[data-print]')) {
      window.print();
    }
  });
})();
