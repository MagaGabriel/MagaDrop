# MagaDrop

Aplicativo Windows para receber arquivos de celulares e outros dispositivos conectados à mesma rede local.

## Como usar

1. Abra o MagaDrop no computador e aguarde o servidor iniciar.
2. No celular, conectado à mesma rede Wi-Fi, leia o QR code ou abra o endereço exibido.
3. Digite no navegador a senha de acesso criada no aplicativo do computador.
4. Selecione ou arraste os arquivos. Nomes existentes não são sobrescritos.
5. Use **Abrir pasta** para acessar os arquivos recebidos.

Na primeira execução, o usuário escolhe onde os arquivos serão salvos. A pasta pode ser alterada depois na janela principal.

## Segurança

- A senha de acesso é criada pelo usuário e permanece salva nas preferências locais do Windows até ser alterada.
- Caminhos e nomes inválidos são rejeitados; o limite por arquivo é 2 GB.
- O tráfego é HTTP local, sem criptografia. Use uma rede confiável e não exponha a porta escolhida pelo aplicativo à internet.

## Desenvolvimento

Requisitos: JDK 25 ou compatível e PowerShell 7.

```powershell
.\build.ps1
.\build.ps1 -Test
```

O script compila as classes, recria `MagaDrop.jar` e atualiza o executável usando `launcher\MagaDropLauncher.bin`. Para gerar o instalador, compile `MagaDrop.iss` no Inno Setup.

- `src/`: servidor HTTP e interface Java Swing.
- `web/`: interface responsiva servida aos dispositivos.
- `tests/`: teste integrado local.
- `jre/`: runtime Java distribuído com o instalador.

`web/qrcode.min.js` é o QRCode.js 1.0.0, distribuído sob licença MIT.
