// Preserve filters, dates and paging when changing language on a GET page.
for (const link of document.querySelectorAll('[data-language]')) {
    const url = new URL(window.location.href);
    // Re-render ToDo forms through a safe POST endpoint, keeping their values and CSRF token.
    const todoForm = document.querySelector('form[data-language-action]');
    if (todoForm) {
        link.addEventListener('click', event => {
            event.preventDefault();
            const language = document.createElement('input');
            language.type = 'hidden';
            language.name = 'lang';
            language.value = link.dataset.language;
            todoForm.appendChild(language);
            const submit = document.createElement('button');
            submit.type = 'submit';
            submit.hidden = true;
            submit.formAction = todoForm.dataset.languageAction;
            submit.formNoValidate = true;
            todoForm.appendChild(submit);
            todoForm.requestSubmit(submit);
        });
    } else {
        url.searchParams.set('lang', link.dataset.language);
        link.href = url.href;
    }
}
