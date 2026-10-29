document.addEventListener('DOMContentLoaded', () => {
    const list = document.getElementById('todo-list');
    if (!list || list.dataset.enabled !== 'true') return;
    const status = document.getElementById('reorder-status');
    let dragged = null;
    let original = [];
    let saving = false;
    let dropped = false;
    const rows = () => Array.from(list.children);
    const restore = () => original.forEach(row => list.appendChild(row));
    const clear = () => rows().forEach(row => row.classList.remove('dragging', 'drag-over'));

    async function save() {
        if (rows().every((row, index) => row === original[index])) return;
        saving = true;
        list.setAttribute('aria-busy', 'true');
        try {
            const response = await fetch(list.dataset.reorderUrl, {
                method: 'PUT',
                headers: {
                    'Content-Type': 'application/json',
                    [document.querySelector('meta[name="_csrf_header"]').content]:
                        document.querySelector('meta[name="_csrf"]').content
                },
                body: JSON.stringify(rows().map((row, index) => ({
                    id: Number(row.dataset.id), displayOrder: index + 1
                })))
            });
            if (!response.ok || response.redirected) throw new Error('Save failed');
            status.textContent = list.dataset.saved;
        } catch (error) {
            restore();
            status.textContent = list.dataset.error;
        } finally {
            saving = false;
            list.removeAttribute('aria-busy');
        }
    }
    list.addEventListener('dragstart', event => {
        const handle = event.target.closest('.drag-handle');
        if (!handle || saving) { event.preventDefault(); return; }
        original = rows();
        dropped = false;
        dragged = handle.closest('tr');
        dragged.classList.add('dragging');
        event.dataTransfer.effectAllowed = 'move';
        event.dataTransfer.setData('text/plain', dragged.dataset.id);
    });
    list.addEventListener('dragover', event => {
        if (!dragged || saving) return;
        event.preventDefault();
        event.dataTransfer.dropEffect = 'move';
        const after = rows().find(row => row !== dragged &&
            event.clientY < row.getBoundingClientRect().top + row.getBoundingClientRect().height / 2);
        clear();
        dragged.classList.add('dragging');
        if (after) after.classList.add('drag-over');
        list.insertBefore(dragged, after || null);
    });
    list.addEventListener('drop', event => {
        if (!dragged || saving) return;
        event.preventDefault();
        dropped = true;
        clear();
        dragged = null;
        void save();
    });
    list.addEventListener('dragend', () => {
        if (!dropped && dragged) restore();
        clear();
        dragged = null;
    });
    list.addEventListener('keydown', event => {
        if (!event.target.matches('.drag-handle') || saving || dragged ||
                !['ArrowUp', 'ArrowDown'].includes(event.key)) return;
        event.preventDefault();
        const row = event.target.closest('tr');
        const neighbor = event.key === 'ArrowUp' ? row.previousElementSibling : row.nextElementSibling;
        if (!neighbor) return;
        original = rows();
        if (event.key === 'ArrowUp') list.insertBefore(row, neighbor);
        else list.insertBefore(neighbor, row);
        event.target.focus();
        void save();
    });
});
