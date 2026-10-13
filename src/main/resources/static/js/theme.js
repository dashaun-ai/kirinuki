(() => {
    const root = document.documentElement;
    const paint = btn => {
        btn.textContent = root.dataset.theme === "dark" ? "☀" : "☾";
        btn.title = root.dataset.theme === "dark" ? "Switch to light mode" : "Switch to dark mode";
    };
    document.querySelectorAll(".theme-toggle").forEach(btn => {
        paint(btn);
        btn.addEventListener("click", () => {
            root.dataset.theme = root.dataset.theme === "dark" ? "light" : "dark";
            localStorage.setItem("kirinuki-theme", root.dataset.theme);
            document.querySelectorAll(".theme-toggle").forEach(paint);
        });
    });
})();
