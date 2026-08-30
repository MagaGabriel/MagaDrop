const drop = document.getElementById("drop");
const input = document.getElementById("files");
const lista = document.getElementById("lista");
const statusEl = document.getElementById("status");
const codigo = document.getElementById("access-code");
const link = document.getElementById("link");
const url = `${window.location.protocol}//${window.location.host}/`;
link.textContent = url; link.href = url;
if (window.QRCode) new QRCode(document.getElementById("qrcode"), {text:url,width:190,height:190,correctLevel:QRCode.CorrectLevel.M});
else document.getElementById("qrcode").textContent = "QR code indisponível";

drop.addEventListener("click", () => input.click());
drop.addEventListener("keydown", e => { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); input.click(); } });
drop.addEventListener("dragover", e => { e.preventDefault(); drop.classList.add("dragging"); });
drop.addEventListener("dragleave", () => drop.classList.remove("dragging"));
drop.addEventListener("drop", e => { e.preventDefault(); drop.classList.remove("dragging"); enviarArquivos([...e.dataTransfer.files]); });
input.addEventListener("change", () => { enviarArquivos([...input.files]); input.value = ""; });
codigo.addEventListener("input", () => { codigo.value = codigo.value.replace(/\D/g, "").slice(0, 6); });

async function enviarArquivos(arquivos) {
  if (!arquivos.length) return;
  if (!/^\d{6}$/.test(codigo.value)) {
    statusEl.textContent = "Digite o código de 6 dígitos exibido no computador."; statusEl.className = "status error"; codigo.focus(); return;
  }
  statusEl.textContent = `Enviando ${arquivos.length} arquivo(s)...`; statusEl.className = "status sending";
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
    xhr.setRequestHeader("X-Filename", arquivo.name); xhr.setRequestHeader("X-Access-Code", codigo.value);
    xhr.upload.onprogress = e => { if (e.lengthComputable) { const p = Math.round(e.loaded/e.total*100); bar.style.width=`${p}%`; texto.textContent=`${p}%`; } };
    xhr.onload = () => {
      if (xhr.status === 201) { bar.style.width="100%"; texto.textContent=`✓ Salvo como ${xhr.responseText}`; item.classList.add("done"); resolve(true); }
      else { texto.textContent=xhr.responseText || `Erro ${xhr.status}`; item.classList.add("failed"); resolve(false); }
    };
    xhr.onerror = () => { texto.textContent="Falha de conexão"; item.classList.add("failed"); resolve(false); }; xhr.send(arquivo);
  });
}
