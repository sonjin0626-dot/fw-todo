'use strict';
document.querySelectorAll('.template-delete').forEach(form => {
    form.addEventListener('submit', event => {
        if (!window.confirm(form.dataset.confirm)) event.preventDefault();
    });
});
const templateItems = document.getElementById('template-items');
if (templateItems) {
    const addButton = document.getElementById('add-template-item');
    const updateButtons = () => {
        const rows = templateItems.querySelectorAll('.template-item-row');
        addButton.disabled = rows.length >= 100;
        rows.forEach(row => { row.querySelector('button').disabled = rows.length <= 1; });
    };
    addButton.addEventListener('click', () => {
        if (templateItems.children.length >= 100) return;
        templateItems.appendChild(document.getElementById('template-item-prototype').content.cloneNode(true));
        templateItems.lastElementChild.querySelector('input').focus();
        updateButtons();
    });
    templateItems.addEventListener('click', event => {
        if (event.target.closest('.remove-template-item') && templateItems.children.length > 1) {
            event.target.closest('.template-item-row').remove();
            updateButtons();
        }
    });
    updateButtons();
}
