(function () {
    'use strict';

    var tabButtons = Array.prototype.slice.call(document.querySelectorAll('[data-tab-trigger]'));
    var tabContents = Array.prototype.slice.call(document.querySelectorAll('[data-tab-content]'));
    var forms = Array.prototype.slice.call(document.querySelectorAll('.auth-form'));

    function activateTab(tabName) {
        tabButtons.forEach(function (button) {
            var isActive = button.getAttribute('data-tab-trigger') === tabName;
            button.classList.toggle('active', isActive);
            button.setAttribute('aria-selected', isActive ? 'true' : 'false');
        });
        tabContents.forEach(function (content) {
            var isActive = content.getAttribute('data-tab-content') === tabName;
            content.classList.toggle('active', isActive);
        });
    }

    tabButtons.forEach(function (button) {
        button.addEventListener('click', function () {
            var tabName = button.getAttribute('data-tab-trigger');
            activateTab(tabName);
        });
    });

    forms.forEach(function (form) {
        form.addEventListener('submit', function (event) {
            event.preventDefault();
            var tab = form.getAttribute('data-tab-content');
            var tier = tab === 'signin' ? 'basic' : 'pro';
            window.location.href = '/index.html?tier=' + tier;
        });
    });
})();
