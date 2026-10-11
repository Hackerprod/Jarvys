"use strict";
const form = document.getElementById("entry-form");
const input = document.getElementById("task");
const rows = document.getElementById("rows");
const status = document.getElementById("status");
const clear = document.getElementById("clear");
function renderState() {
  const count = rows.children.length;
  document.getElementById("empty").hidden = count !== 0;
  document.getElementById("tasks").hidden = count === 0;
  clear.disabled = count === 0;
}
form.addEventListener("submit", event => {
  event.preventDefault();
  const value = input.value.trim();
  if (!value || value.length > 80) { status.textContent = "Enter a task of 1–80 characters."; input.focus(); return; }
  if (rows.children.length >= 20) { status.textContent = "This demo holds up to 20 tasks. Clear the list to start again."; return; }
  const row = document.createElement("tr");
  for (const text of [value, "Planned"]) { const cell = document.createElement("td"); cell.textContent = text; row.appendChild(cell); }
  rows.appendChild(row); input.value = ""; renderState(); status.textContent = "Added for this session only."; input.focus();
});
clear.addEventListener("click", () => { rows.replaceChildren(); renderState(); status.textContent = "Session list cleared."; input.focus(); });
renderState();
