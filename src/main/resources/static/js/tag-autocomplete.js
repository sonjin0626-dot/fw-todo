document.addEventListener('DOMContentLoaded', () => {
    const input = document.getElementById('tagInput');
    if (!input) return;
    const wrapper = input.closest('.tag-input-wrapper');
    const dropdown = document.getElementById('tagDropdown');
    const selected = document.getElementById('selectedTags');
    const status = document.getElementById('tagStatus');
    let timer, controller, version = 0, active = -1, tags = [];
    const ids = () => new Set([...selected.querySelectorAll('input[name="tagIds"]')].map(el => el.value));
    function close() {
        clearTimeout(timer);
        controller?.abort();
        version++;
        dropdown.hidden = true;
        input.setAttribute('aria-expanded', 'false');
        input.removeAttribute('aria-activedescendant');
        active = -1;
    }
    function highlight() {
        [...dropdown.children].forEach((el, index) => {
            el.classList.toggle('active', index === active);
            el.setAttribute('aria-selected', String(index === active));
        });
        if (active >= 0) {
            input.setAttribute('aria-activedescendant', dropdown.children[active].id);
            dropdown.children[active].scrollIntoView({ block: 'nearest' });
        }
    }
    function choose(tag) {
        if (ids().has(String(tag.id))) return;
        const chip = document.createElement('span');
        chip.className = 'tag-chip';
        const label = document.createElement('span');
        label.textContent = tag.name;
        const remove = document.createElement('button');
        remove.type = 'button';
        remove.className = 'remove-tag';
        remove.textContent = '×';
        remove.setAttribute('aria-label', wrapper.dataset.removeLabel);
        const hidden = document.createElement('input');
        hidden.type = 'hidden';
        hidden.name = 'tagIds';
        hidden.value = tag.id;
        chip.append(label, remove, hidden);
        selected.append(chip);
        input.value = '';
        close();
        input.focus();
    }
    input.addEventListener('input', event => {
        close();
        if (event.isComposing) return;
        const query = input.value.trim();
        if (!query) return;
        const requestVersion = version;
        timer = setTimeout(async () => {
            controller = new AbortController();
            try {
                const response = await fetch(`${wrapper.dataset.searchUrl}?q=${encodeURIComponent(query)}`, { signal: controller.signal });
                if (!response.ok) throw new Error('Tag search failed');
                const result = await response.json();
                if (requestVersion !== version) return;
                tags = result.filter(tag => !ids().has(String(tag.id)));
                dropdown.replaceChildren();
                tags.forEach((tag, index) => {
                    const item = document.createElement('div');
                    item.className = 'suggestion-item';
                    item.id = `tag-option-${index}`;
                    item.setAttribute('role', 'option');
                    item.setAttribute('aria-selected', 'false');
                    item.textContent = tag.name;
                    item.addEventListener('mousedown', event => event.preventDefault());
                    item.addEventListener('click', () => choose(tag));
                    dropdown.append(item);
                });
                dropdown.hidden = tags.length === 0;
                input.setAttribute('aria-expanded', String(tags.length > 0));
                status.textContent = '';
            } catch (error) {
                if (error.name !== 'AbortError' && requestVersion === version) {
                    close();
                    status.textContent = wrapper.dataset.errorLabel;
                }
            }
        }, 300);
    });
    input.addEventListener('compositionend', () => input.dispatchEvent(new Event('input')));
    input.addEventListener('keydown', event => {
        if (event.isComposing) return;
        if (event.key === 'Escape' || event.key === 'Tab') close();
        if (dropdown.hidden) return;
        if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
            event.preventDefault();
            active = (active + (event.key === 'ArrowDown' ? 1 : (active < 0 ? 0 : -1)) + tags.length) % tags.length;
            highlight();
        } else if (event.key === 'Enter') {
            event.preventDefault();
            if (active >= 0) choose(tags[active]);
        }
    });
    selected.addEventListener('click', event => {
        const button = event.target.closest('.remove-tag');
        if (!button) return;
        button.closest('.tag-chip').remove();
        close();
        input.focus();
    });
    document.addEventListener('click', event => { if (!wrapper.contains(event.target)) close(); });
    wrapper.addEventListener('focusout', event => { if (!wrapper.contains(event.relatedTarget)) close(); });
    input.form.addEventListener('submit', close);
});