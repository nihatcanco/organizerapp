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
    const width = image.naturalWidth || image.width;
    const height = image.naturalHeight || image.height;
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

  // ---------- Fill in item details from a photo, read by the AI model on the server ----------

  for (const button of document.querySelectorAll('[data-read-photo]')) {
    const form = button.form;
    const photoInput = form.querySelector('input[data-photo-input]');
    const update = () => { button.disabled = !(photoInput.files.length || button.dataset.readPhoto); };
    button.closest('.ai-reader').hidden = false;
    update();
    button.addEventListener('click', () => readPhoto(button));
    photoInput.addEventListener('photo-ready', async () => {
      update();
      // Once the model is downloaded, a new item's photo is read straight away.
      if (photoInput.files.length && !form.elements.namedItem('name').value.trim()) {
        const status = await aiStatus().catch(() => null);
        if (status && status.state === 'ready') readPhoto(button);
      }
    });
  }

  async function aiStatus() {
    const response = await fetch('/ai/status', { headers: { Accept: 'application/json' } });
    return response.json();
  }

  async function readPhoto(button) {
    const form = button.form;
    const status = form.querySelector('[data-ai-status]');
    const show = (message, kind = '') => {
      status.hidden = false;
      status.className = `ai-status ${kind}`;
      status.textContent = message;
    };
    button.disabled = true;
    try {
      let state = await aiStatus();
      if (state.state === 'missing' || state.state === 'failed') {
        const size = Math.round(state.total / 1e6);
        const question = 'Reading photos uses a small AI model that runs on this computer. '
          + `It needs a one-time download of about ${size} MB. Download it now?`;
        if (!window.confirm(question)) return;
        state = await (await fetch('/ai/download', { method: 'POST', headers: { Accept: 'application/json' } })).json();
      }
      while (state.state === 'downloading') {
        show(`Downloading the AI model… ${Math.floor((100 * state.done) / state.total)}%`);
        await new Promise((resolve) => setTimeout(resolve, 1000));
        state = await aiStatus();
      }
      if (state.state !== 'ready') throw new Error(state.error || 'the model could not be downloaded');

      show('Reading the photo…');
      const photoInput = form.querySelector('input[data-photo-input]');
      await photoInput.photoReady;
      const body = new FormData();
      if (photoInput.files.length) body.append('photo', photoInput.files[0]);
      else body.append('item', button.dataset.readPhoto);
      const response = await fetch('/ai/read', { method: 'POST', body, headers: { Accept: 'application/json' } });
      const result = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(result.error || `the server answered ${response.status}`);

      const found = result.name || result.quantity || result.notes || (result.tags || []).length || (result.properties || []).length;
      const filled = fillItemForm(form, result);
      if (filled.length) {
        show(`Filled in ${filled.join(', ')}. Please check before saving.`, 'ai-ok');
      } else if (found) {
        show('Nothing new to fill in: the form already has what the photo shows.', 'ai-ok');
      } else {
        show('No details found. A sharp, close photo of the label works best.', 'ai-warn');
      }
      if (result.text && result.text.length) {
        const details = document.createElement('details');
        const summary = document.createElement('summary');
        const text = document.createElement('pre');
        summary.textContent = 'Text found in the photo';
        text.textContent = result.text.join('\n');
        details.append(summary, text);
        status.append(details);
      }
    } catch (error) {
      show(`Couldn't read the photo: ${error.message}.`, 'ai-error');
    } finally {
      button.disabled = false;
    }
  }

  /** Puts the suggestions into the form without overwriting anything already typed. */
  function fillItemForm(form, result) {
    const filled = [];
    const field = (name) => form.elements.namedItem(name);
    if (result.name && !field('name').value.trim()) {
      field('name').value = result.name;
      filled.push('the name');
    }
    if (result.quantity && ['', '1'].includes(field('quantity').value.trim())) {
      field('quantity').value = result.quantity;
      filled.push('the quantity');
    }
    const tags = field('tags').value.split(',').map((tag) => tag.trim()).filter(Boolean);
    const newTags = (result.tags || []).filter((tag) => !tags.some((existing) => existing.toLowerCase() === tag.toLowerCase()));
    if (newTags.length) {
      field('tags').value = [...tags, ...newTags].join(', ');
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
      row.querySelector('[name="property_name"]').value = property.name;
      row.querySelector('[name="property_value"]').value = property.value;
      added += 1;
    }
    if (added) {
      filled.push(count(added, 'property', 'properties'));
      const blank = rows.lastElementChild.cloneNode(true); // keep an empty row for typing more
      for (const input of blank.querySelectorAll('input')) input.value = '';
      rows.append(blank);
    }
    if (result.notes && !field('notes').value.trim()) {
      field('notes').value = result.notes;
      filled.push('notes');
    }
    return filled;
  }

  const count = (n, word, plural = `${word}s`) => `${n} ${n === 1 ? word : plural}`;

  // ---------- Small click helpers ----------

  document.addEventListener('click', (event) => {
    const target = event.target.closest('[data-add-property], [data-remove-property], [data-add-tag], [data-remove-label], [data-print]');
    if (!target) return;

    if (target.matches('[data-add-property]')) {
      const rows = target.form.querySelector('[data-property-rows]');
      const row = rows.lastElementChild.cloneNode(true);
      for (const field of row.querySelectorAll('input')) field.value = '';
      rows.append(row);
      row.querySelector('input').focus();
    } else if (target.matches('[data-remove-property]')) {
      const row = target.closest('.property-row');
      if (row.parentElement.children.length > 1) {
        row.remove();
      } else {
        for (const field of row.querySelectorAll('input')) field.value = '';
      }
    } else if (target.matches('[data-add-tag]')) {
      const input = target.closest('.field').querySelector('input[name="tags"]');
      const tags = input.value.split(',').map((tag) => tag.trim()).filter(Boolean);
      const tag = target.dataset.addTag;
      if (!tags.some((existing) => existing.toLowerCase() === tag.toLowerCase())) tags.push(tag);
      input.value = tags.join(', ');
      target.classList.add('chip-active');
    } else if (target.matches('[data-remove-label]')) {
      target.closest('.label').remove();
    } else if (target.matches('[data-print]')) {
      window.print();
    }
  });
})();
