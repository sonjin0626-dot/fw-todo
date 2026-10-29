document.addEventListener('DOMContentLoaded', () => {
    const form = document.getElementById('quick-todo-form');
    if (!form) return;
    const input = document.getElementById('quick-todo-title');
    const button = form.querySelector('button[type="submit"]');
    const error = document.getElementById('quick-todo-error');
    const status = document.getElementById('quick-todo-status');
    let sending = false;

    const clearError = () => {
        error.textContent = '';
        error.hidden = true;
        input.removeAttribute('aria-invalid');
    };
    input.addEventListener('input', () => {
        clearError();
        status.textContent = '';
    });
    // Enter used to confirm Japanese IME input must not create a task.
    input.addEventListener('keydown', event => {
        if (event.key === 'Enter' && (event.isComposing || event.keyCode === 229)) {
            event.preventDefault();
        }
    });

    function insertRow(todo) {
        const row = document.getElementById('quick-todo-row').content.firstElementChild.cloneNode(true);
        const id = String(todo.id);
        row.dataset.id = id;
        row.dataset.completed = 'false';
        row.dataset.dueDate = '';
        // Use textContent rather than interpreting the user-supplied title as HTML.
        row.querySelector('[data-quick-title]').textContent = todo.title;
        row.querySelectorAll('[href], [action]').forEach(element => {
            const attribute = element.hasAttribute('href') ? 'href' : 'action';
            element.setAttribute(attribute, element.getAttribute(attribute).replace(/\/0(?=\/|\?|$)/, `/${id}`));
        });
        row.querySelectorAll('input[type="checkbox"]').forEach(box => {
            box.value = id;
            box.setAttribute('aria-label', todo.title);
        });
        row.classList.add('quick-added');
        row.addEventListener('animationend', () => row.classList.remove('quick-added'), { once: true });
        document.getElementById('todo-list').prepend(row);
        document.getElementById('todo-table').hidden = false;
        document.getElementById('todo-empty')?.remove();
        document.dispatchEvent(new CustomEvent('todo:created', { detail: todo }));
    }

    form.addEventListener('submit', async event => {
        event.preventDefault();
        if (sending) return;
        sending = true;
        clearError();
        status.textContent = '';
        button.disabled = true;
        input.readOnly = true;
        form.setAttribute('aria-busy', 'true');
        try {
            const response = await fetch(form.action, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {
                    'Content-Type': 'application/json',
                    Accept: 'application/json',
                    [document.querySelector('meta[name="_csrf_header"]').content]:
                        document.querySelector('meta[name="_csrf"]').content
                },
                body: JSON.stringify({ title: input.value })
            });
            if (response.redirected || response.status === 401 || response.status === 403) {
                throw new Error(form.dataset.sessionError);
            }
            const data = await response.json();
            if (!response.ok) {
                const titleError = data.errors?.find(item => item.field === 'title');
                throw new Error(titleError?.message || data.detail || form.dataset.error);
            }
            insertRow(data);
            input.value = '';
            status.textContent = form.dataset.success;
        } catch (failure) {
            error.textContent = failure instanceof SyntaxError || failure instanceof TypeError
                ? form.dataset.error : failure.message;
            error.hidden = false;
            input.setAttribute('aria-invalid', 'true');
        } finally {
            sending = false;
            button.disabled = false;
            input.readOnly = false;
            form.removeAttribute('aria-busy');
            input.focus();
        }
    });
});
