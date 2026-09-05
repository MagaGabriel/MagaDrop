const loginCard = document.getElementById("login-card");
const appCard = document.getElementById("app-card");
const loginForm = document.getElementById("login-form");
const loginStatus = document.getElementById("login-status");
const username = document.getElementById("username");
const password = document.getElementById("password");
const displayName = document.getElementById("display-name");
const userRole = document.getElementById("user-role");
const logoutButton = document.getElementById("logout");
const drop = document.getElementById("drop");
const input = document.getElementById("files");
const lista = document.getElementById("lista");
const statusEl = document.getElementById("status");
const link = document.getElementById("link");
let csrfToken = "";

const url = `${window.location.protocol}//${window.location.host}/`;
link.textContent = url;
link.href = url;
if (window.QRCode) new QRCode(document.getElementById("qrcode"), {text:url,width:190,height:190,correctLevel:QRCode.CorrectLevel.M});
else document.getElementById("qrcode").textContent = "QR code indisponível";

loginForm.addEventListener("submit", entrar);
logoutButton.addEventListener("click", sair);
drop.addEventListener("click", () => input.click());
drop.addEventListener("keydown", event => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); input.click(); } });
drop.addEventListener("dragover", event => { event.preventDefault(); drop.classList.add("dragging"); });
drop.addEventListener("dragleave", () => drop.classList.remove("dragging"));
drop.addEventListener("drop", event => { event.preventDefault(); drop.classList.remove("dragging"); enviarArquivos([...event.dataTransfer.files]); });
input.addEventListener("change", () => { enviarArquivos([...input.files]); input.value = ""; });

carregarSessao();

async function carregarSessao() {
  try {
    const response = await fetch("/api/session", {cache:"no-store"});
    if (!response.ok) return mostrarLogin();
    mostrarAplicacao(await response.json());
  } catch {
    loginStatus.textContent = "Não foi possível conectar ao MagaDrop.";
    loginStatus.className = "status error";
  }
}

async function entrar(event) {
  event.preventDefault();
  loginStatus.textContent = "Entrando...";
  loginStatus.className = "status sending";
  const body = new URLSearchParams({username:username.value, password:password.value});
  try {
    const response = await fetch("/api/session", {
      method:"POST",
      headers:{"Content-Type":"application/x-www-form-urlencoded;charset=UTF-8"},
      body
    });
    const data = await response.json();
    if (!response.ok) {
      loginStatus.textContent = data.error || "Não foi possível entrar.";
      loginStatus.className = "status error";
      password.select();
      return;
    }
    password.value = "";
    mostrarAplicacao(data);
  } catch {
    loginStatus.textContent = "Falha de conexão com o servidor.";
    loginStatus.className = "status error";
  }
}

async function sair() {
  try {
    await fetch("/api/session", {method:"DELETE", headers:{"X-CSRF-Token":csrfToken}});
  } finally {
    mostrarLogin();
  }
}

function mostrarAplicacao(data) {
  csrfToken = data.csrfToken;
  displayName.textContent = data.user.displayName;
  userRole.textContent = data.user.role === "admin" ? `@${data.user.username} · Administrador` : `@${data.user.username} · Membro`;
  loginCard.hidden = true;
  appCard.hidden = false;
  statusEl.textContent = "Pronto para receber";
  statusEl.className = "status";
}

function mostrarLogin() {
  csrfToken = "";
  appCard.hidden = true;
  loginCard.hidden = false;
  lista.replaceChildren();
  loginStatus.textContent = "Use a conta configurada no computador.";
  loginStatus.className = "status";
  password.value = "";
  username.focus();
}

async function enviarArquivos(arquivos) {
  if (!arquivos.length) return;
  statusEl.textContent = `Enviando ${arquivos.length} arquivo(s)...`;
  statusEl.className = "status sending";
  const resultados = await Promise.all(arquivos.map(enviar));
  const sucessos = resultados.filter(Boolean).length;
  statusEl.textContent = sucessos === arquivos.length ? `✓ ${sucessos} arquivo(s) enviado(s)` : `${sucessos} de ${arquivos.length} arquivo(s) enviados`;
  statusEl.className = sucessos === arquivos.length ? "status success" : "status error";
}

function enviar(arquivo) {
  const item = document.createElement("div"); item.className = "item";
  const nome = document.createElement("div"); nome.className = "file-name"; nome.textContent = arquivo.name;
  const progress = document.createElement("div"); progress.className = "progress";
  const bar = document.createElement("div"); bar.className = "bar"; progress.appendChild(bar);
  const texto = document.createElement("div"); texto.className = "file-status"; texto.textContent = "Aguardando";
  item.append(nome, progress, texto); lista.prepend(item);
  return new Promise(resolve => {
    const xhr = new XMLHttpRequest(); xhr.open("POST", "/upload");
    xhr.setRequestHeader("X-Filename", arquivo.name); xhr.setRequestHeader("X-CSRF-Token", csrfToken);
    xhr.upload.onprogress = event => { if (event.lengthComputable) { const percentage = Math.round(event.loaded/event.total*100); bar.style.width=`${percentage}%`; texto.textContent=`${percentage}%`; } };
    xhr.onload = () => {
      if (xhr.status === 201) { bar.style.width="100%"; texto.textContent=`✓ Salvo como ${xhr.responseText}`; item.classList.add("done"); resolve(true); }
      else {
        texto.textContent=xhr.responseText || `Erro ${xhr.status}`; item.classList.add("failed");
        if (xhr.status === 401) mostrarLogin();
        resolve(false);
      }
    };
    xhr.onerror = () => { texto.textContent="Falha de conexão"; item.classList.add("failed"); resolve(false); };
    xhr.send(arquivo);
  });
}
