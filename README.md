# MagaDrop

Aplicativo Windows para receber arquivos de celulares e outros dispositivos conectados à mesma rede local.

> A branch `codex/v3-contas-sessoes` contém a fundação da versão 3 em desenvolvimento. A versão estável continua preservada na tag e na Release `v2.0.0`.

## Como usar

1. Abra o MagaDrop no computador e aguarde o servidor iniciar.
2. No celular, conectado à mesma rede Wi-Fi, leia o QR code ou abra o endereço exibido.
3. Entre com o usuário `admin` e a senha configurada no aplicativo do computador.
4. Selecione ou arraste os arquivos. Nomes existentes não são sobrescritos.
5. Use **Abrir pasta** para acessar os arquivos recebidos.

Na primeira execução, o usuário escolhe onde os arquivos serão salvos. A pasta pode ser alterada depois na janela principal.

## Fundação de segurança da versão 3

- A senha da conta é protegida com PBKDF2-HMAC-SHA-256, salt aleatório e 600 mil iterações.
- A senha legada da versão 2 é migrada para a conta `admin` e removida das preferências do Windows.
- O navegador utiliza uma sessão aleatória de 256 bits em cookie `HttpOnly` e `SameSite=Strict`.
- Operações de escrita exigem também um token contra requisições forjadas (CSRF).
- Cinco falhas de login consecutivas bloqueiam novas tentativas por um minuto.
- Sessões expiram após uma hora sem atividade ou doze horas no total.
- Caminhos e nomes inválidos são rejeitados; o limite por arquivo é 2 GB.
- O tráfego é HTTP local, sem criptografia. Use uma rede confiável e não exponha a porta escolhida pelo aplicativo à internet.

A versão 3 já inclui administração de usuários no aplicativo Windows e no navegador: criação de membros, redefinição de senha, ativação, desativação, encerramento de sessões e troca da própria senha. As próximas entregas são pastas pessoais e compartilhadas, explorador de arquivos e downloads. Consulte [V3_DESENVOLVIMENTO.md](V3_DESENVOLVIMENTO.md).

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
