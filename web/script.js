const loginCard = document.getElementById("login-card");
const appCard = document.getElementById("app-card");
const loginForm = document.getElementById("login-form");
const loginStatus = document.getElementById("login-status");
const username = document.getElementById("username");
const password = document.getElementById("password");
const displayName = document.getElementById("display-name");
const userRole = document.getElementById("user-role");
const logoutButton = document.getElementById("logout");
const adminNav = document.getElementById("admin-nav");
const navButtons = [...document.querySelectorAll(".nav-button")];
const views = [...document.querySelectorAll(".view")];
const drop = document.getElementById("drop");
const input = document.getElementById("files");
const lista = document.getElementById("lista");
const statusEl = document.getElementById("status");
const accountPasswordForm = document.getElementById("account-password-form");
const accountStatus = document.getElementById("account-status");
const createUserForm = document.getElementById("create-user-form");
const createUserStatus = document.getElementById("create-user-status");
const usersList = document.getElementById("users-list");
const adminActionPanel = document.getElementById("admin-action-panel");
const adminActionForm = document.getElementById("admin-action-form");
const adminActionTitle = document.getElementById("admin-action-title");
const adminActionDescription = document.getElementById("admin-action-description");
const adminActionStatus = document.getElementById("admin-action-status");
const actionNewPasswordFields = document.getElementById("action-new-password-fields");
const link = document.getElementById("link");
let csrfToken = "";
let currentUser = null;
let selectedAdminAction = null;

const url = `${window.location.protocol}//${window.location.host}/`;
link.textContent = url;
link.href = url;
if (window.QRCode) new QRCode(document.getElementById("qrcode"), {text:url,width:190,height:190,correctLevel:QRCode.CorrectLevel.M});
else document.getElementById("qrcode").textContent = "QR code indisponível";

loginForm.addEventListener("submit", enter);
logoutButton.addEventListener("click", logout);
navButtons.forEach(button => button.addEventListener("click", () => showView(button.dataset.view)));
accountPasswordForm.addEventListener("submit", changeOwnPassword);
createUserForm.addEventListener("submit", createUser);
adminActionForm.addEventListener("submit", runAdminAction);
document.getElementById("cancel-admin-action").addEventListener("click", closeAdminAction);
drop.addEventListener("click", () => input.click());
drop.addEventListener("keydown", event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); input.click(); } });
drop.addEventListener("dragover", event => { event.preventDefault(); drop.classList.add("dragging"); });
drop.addEventListener("dragleave", () => drop.classList.remove("dragging"));
drop.addEventListener("drop", event => { event.preventDefault(); drop.classList.remove("dragging"); sendFiles([...event.dataTransfer.files]); });
input.addEventListener("change", () => { sendFiles([...input.files]); input.value = ""; });

loadSession();

async function loadSession() {
  try {
    const response = await fetch("/api/session", {cache:"no-store"});
    if (!response.ok) return showLogin();
    showApplication(await response.json());
  } catch {
    setStatus(loginStatus, "Não foi possível conectar ao MagaDrop.", "error");
  }
}

async function enter(event) {
  event.preventDefault();
  setStatus(loginStatus, "Entrando...", "sending");
  try {
    const response = await postForm("/api/session", {username:username.value, password:password.value}, false);
    const data = await response.json();
    if (!response.ok) {
      setStatus(loginStatus, data.error || "Não foi possível entrar.", "error");
      password.select();
      return;
    }
    password.value = "";
    showApplication(data);
  } catch {
    setStatus(loginStatus, "Falha de conexão com o servidor.", "error");
  }
}

async function logout() {
  try { await fetch("/api/session", {method:"DELETE", headers:{"X-CSRF-Token":csrfToken}}); }
  finally { showLogin(); }
}

function showApplication(data) {
  csrfToken = data.csrfToken;
  currentUser = data.user;
  displayName.textContent = currentUser.displayName;
  userRole.textContent = currentUser.role === "admin" ? `@${currentUser.username} · Administrador` : `@${currentUser.username} · Membro`;
  adminNav.hidden = currentUser.role !== "admin";
  loginCard.hidden = true;
  appCard.hidden = false;
  setStatus(statusEl, "Pronto para receber");
  showView("upload-view");
}

function showLogin(message = "Use a conta configurada no computador.") {
  csrfToken = "";
  currentUser = null;
  selectedAdminAction = null;
  appCard.hidden = true;
  loginCard.hidden = false;
  lista.replaceChildren();
  setStatus(loginStatus, message, message.startsWith("Senha alterada") ? "success" : "");
  password.value = "";
  username.focus();
}

function showView(viewId) {
  if (viewId === "admin-view" && currentUser?.role !== "admin") return;
  views.forEach(view => view.hidden = view.id !== viewId);
  navButtons.forEach(button => button.classList.toggle("active", button.dataset.view === viewId));
  if (viewId === "admin-view") loadUsers();
}

async function changeOwnPassword(event) {
  event.preventDefault();
  const currentPassword = document.getElementById("current-password");
  const newPassword = document.getElementById("new-password");
  const confirmation = document.getElementById("confirm-new-password");
  if (newPassword.value !== confirmation.value) {
    setStatus(accountStatus, "As novas senhas não são iguais.", "error"); return;
  }
  setStatus(accountStatus, "Alterando senha...", "sending");
  try {
    const response = await postForm("/api/account/password", {currentPassword:currentPassword.value, newPassword:newPassword.value});
    if (!response.ok) { setStatus(accountStatus, await apiError(response), "error"); return; }
    accountPasswordForm.reset();
    showLogin("Senha alterada. Entre novamente nos seus dispositivos.");
  } catch { setStatus(accountStatus, "Falha de conexão com o servidor.", "error"); }
}

async function loadUsers() {
  usersList.replaceChildren(messageNode("Carregando usuários..."));
  try {
    const response = await fetch("/api/users", {cache:"no-store"});
    if (response.status === 401) return showLogin();
    if (!response.ok) { usersList.replaceChildren(messageNode(await apiError(response), "error")); return; }
    renderUsers((await response.json()).users);
  } catch { usersList.replaceChildren(messageNode("Não foi possível carregar os usuários.", "error")); }
}

function renderUsers(users) {
  usersList.replaceChildren();
  for (const user of users) {
    const card = document.createElement("article"); card.className = "user-card";
    const info = document.createElement("div"); info.className = "user-info";
    const title = document.createElement("strong"); title.textContent = user.displayName;
    const meta = document.createElement("span");
    meta.textContent = `@${user.username} · ${user.role === "admin" ? "Administrador" : "Membro"} · ${user.enabled ? "Ativa" : "Desativada"} · ${user.activeSessions} sessão(ões)`;
    info.append(title, meta); card.append(info);
    if (user.role !== "admin") {
      const actions = document.createElement("div"); actions.className = "user-actions";
      actions.append(actionButton("Redefinir senha", () => openAdminAction("reset-password", user)));
      actions.append(actionButton(user.enabled ? "Desativar" : "Ativar", () => openAdminAction("set-enabled", user)));
      actions.append(actionButton("Encerrar sessões", () => openAdminAction("revoke-sessions", user)));
      card.append(actions);
    }
    usersList.append(card);
  }
}

async function createUser(event) {
  event.preventDefault();
  const newPassword = document.getElementById("create-password");
  const confirmation = document.getElementById("create-confirm-password");
  if (newPassword.value !== confirmation.value) {
    setStatus(createUserStatus, "As senhas não são iguais.", "error"); return;
  }
  setStatus(createUserStatus, "Criando usuário...", "sending");
  try {
    const response = await postForm("/api/users", {
      action:"create",
      username:document.getElementById("create-username").value,
      displayName:document.getElementById("create-display-name").value,
      password:newPassword.value,
      currentPassword:document.getElementById("create-admin-password").value
    });
    if (!response.ok) { setStatus(createUserStatus, await apiError(response), "error"); return; }
    createUserForm.reset();
    setStatus(createUserStatus, "Membro criado com sucesso.", "success");
    await loadUsers();
  } catch { setStatus(createUserStatus, "Falha de conexão com o servidor.", "error"); }
}

function openAdminAction(action, user) {
  selectedAdminAction = {action, user};
  const reset = action === "reset-password";
  actionNewPasswordFields.hidden = !reset;
  document.getElementById("action-new-password").required = reset;
  document.getElementById("action-confirm-password").required = reset;
  adminActionTitle.textContent = action === "reset-password" ? "Redefinir senha" : action === "set-enabled" ? (user.enabled ? "Desativar usuário" : "Ativar usuário") : "Encerrar sessões";
  adminActionDescription.textContent = action === "reset-password" ? `Defina uma nova senha para @${user.username}. As sessões atuais serão encerradas.`
    : action === "set-enabled" ? `${user.enabled ? "Desativar" : "Ativar"} o acesso de @${user.username}.`
    : `Encerrar todas as sessões de @${user.username}.`;
  adminActionForm.reset();
  setStatus(adminActionStatus, "");
  adminActionPanel.hidden = false;
  adminActionPanel.scrollIntoView({behavior:"smooth", block:"nearest"});
}

function closeAdminAction() {
  selectedAdminAction = null;
  adminActionForm.reset();
  adminActionPanel.hidden = true;
}

async function runAdminAction(event) {
  event.preventDefault();
  if (!selectedAdminAction) return;
  const {action, user} = selectedAdminAction;
  const data = {action, username:user.username, currentPassword:document.getElementById("action-admin-password").value};
  if (action === "reset-password") {
    const newPassword = document.getElementById("action-new-password").value;
    if (newPassword !== document.getElementById("action-confirm-password").value) {
      setStatus(adminActionStatus, "As senhas não são iguais.", "error"); return;
    }
    data.password = newPassword;
  }
  if (action === "set-enabled") data.enabled = String(!user.enabled);
  setStatus(adminActionStatus, "Confirmando ação...", "sending");
  try {
    const response = await postForm("/api/users", data);
    if (!response.ok) { setStatus(adminActionStatus, await apiError(response), "error"); return; }
    closeAdminAction();
    await loadUsers();
  } catch { setStatus(adminActionStatus, "Falha de conexão com o servidor.", "error"); }
}

function actionButton(label, handler) {
  const button = document.createElement("button"); button.type = "button"; button.className = "small-button"; button.textContent = label;
  button.addEventListener("click", handler); return button;
}

function messageNode(message, type = "") {
  const node = document.createElement("p"); node.className = `empty-message ${type}`; node.textContent = message; return node;
}

async function postForm(path, values, includeCsrf = true) {
  const headers = {"Content-Type":"application/x-www-form-urlencoded;charset=UTF-8"};
  if (includeCsrf) headers["X-CSRF-Token"] = csrfToken;
  return fetch(path, {method:"POST", headers, body:new URLSearchParams(values)});
}

async function apiError(response) {
  if (response.status === 401) { showLogin(); return "Sua sessão terminou."; }
  try { return (await response.json()).error || `Erro ${response.status}`; }
  catch { return `Erro ${response.status}`; }
}

function setStatus(element, message, type = "") {
  element.textContent = message;
  element.className = `status ${type}`.trim();
}

async function sendFiles(files) {
  if (!files.length) return;
  setStatus(statusEl, `Enviando ${files.length} arquivo(s)...`, "sending");
  const results = await Promise.all(files.map(sendFile));
  const successes = results.filter(Boolean).length;
  setStatus(statusEl, successes === files.length ? `✓ ${successes} arquivo(s) enviado(s)` : `${successes} de ${files.length} arquivo(s) enviados`, successes === files.length ? "success" : "error");
}

function sendFile(file) {
  const item = document.createElement("div"); item.className = "item";
  const name = document.createElement("div"); name.className = "file-name"; name.textContent = file.name;
  const progress = document.createElement("div"); progress.className = "progress";
  const bar = document.createElement("div"); bar.className = "bar"; progress.appendChild(bar);
  const text = document.createElement("div"); text.className = "file-status"; text.textContent = "Aguardando";
  item.append(name, progress, text); lista.prepend(item);
  return new Promise(resolve => {
    const xhr = new XMLHttpRequest(); xhr.open("POST", "/upload");
    xhr.setRequestHeader("X-Filename", file.name); xhr.setRequestHeader("X-CSRF-Token", csrfToken);
    xhr.upload.onprogress = event => { if (event.lengthComputable) { const percentage = Math.round(event.loaded/event.total*100); bar.style.width=`${percentage}%`; text.textContent=`${percentage}%`; } };
    xhr.onload = () => {
      if (xhr.status === 201) { bar.style.width="100%"; text.textContent=`✓ Salvo como ${xhr.responseText}`; item.classList.add("done"); resolve(true); }
      else { text.textContent=xhr.responseText || `Erro ${xhr.status}`; item.classList.add("failed"); if (xhr.status === 401) showLogin(); resolve(false); }
    };
    xhr.onerror = () => { text.textContent="Falha de conexão"; item.classList.add("failed"); resolve(false); };
    xhr.send(file);
  });
}
