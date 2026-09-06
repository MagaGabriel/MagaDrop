# MagaDrop

O MagaDrop transforma um computador Windows em um servidor de arquivos para a família dentro da rede local. Celulares, tablets e outros computadores acessam pelo navegador, sem instalar aplicativo cliente.

## Instalação

O usuário precisa instalar somente `MagaDropSetup.exe`. O instalador já inclui o Java necessário, a interface web, o ícone, os atalhos e o desinstalador.

Na primeira abertura de uma instalação nova:

1. Clique em **Escolher...** e selecione a pasta base.
2. Crie e confirme a senha do administrador `MAGA`.
3. Se quiser, marque a opção para iniciar o MagaDrop com o Windows.
4. Conclua a configuração e aguarde o servidor ficar pronto.

O MagaDrop cria esta estrutura dentro da pasta escolhida:

```text
Pasta base
├── Compartilhada
└── Usuarios
    └── uma pasta exclusiva para cada conta
```

Cada instalação escolhe sua própria pasta base. O caminho fica salvo somente nas configurações locais e não faz parte do instalador nem do repositório.

## Como usar

1. Abra o MagaDrop no computador que armazenará os arquivos.
2. Conecte o celular e o computador à mesma rede Wi-Fi.
3. Leia o QR code ou digite no celular o endereço exibido pelo programa.
4. Entre com `MAGA` ou com uma conta criada pelo administrador.
5. Escolha **Pessoal** ou **Compartilhada**.
6. Envie arquivos, crie pastas, navegue ou faça downloads.

Ao fechar a janela, o servidor continua ativo na bandeja do Windows. Use **Sair** no ícone da bandeja para encerrá-lo.

## Recursos atuais

- contas locais de administrador e membros;
- pastas pessoais isoladas e uma pasta compartilhada;
- envio de vários arquivos e arrastar e soltar;
- criação de pastas, navegação e download pelo navegador;
- exclusão recuperável em uma lixeira interna;
- QR code gerado sem depender da internet;
- porta alternativa automática quando a 8080 estiver ocupada;
- opção de iniciar junto com o Windows;
- limite de 2 GB por arquivo e preservação de nomes repetidos.

## Segurança e limite atual

- As senhas usam PBKDF2-HMAC-SHA-256 com salt aleatório e 600 mil iterações.
- As sessões usam tokens aleatórios, cookie `HttpOnly`, `SameSite=Strict` e proteção CSRF.
- O servidor limita tentativas de login e rejeita caminhos, links e nomes perigosos.
- Downloads e operações de arquivo não recebem caminhos absolutos do Windows.
- O tráfego atual usa HTTP e deve permanecer em uma rede local confiável.
- Não exponha a porta do MagaDrop diretamente na internet.

O acesso remoto seguro ainda será um marco futuro, depois da adoção de HTTPS e autorização de dispositivos.

## Compartilhar e colaborar

Para outra pessoa apenas usar o programa, envie `output\MagaDropSetup.exe`. Contas, senhas e arquivos deste computador não fazem parte do instalador.

Para colaborar no código, clone a branch principal do repositório público. Quem quiser contribuir pode criar um fork e enviar uma pull request.

Veja também:

- [Apresentação do MagaDrop](apresentacao/APRESENTACAO_MAGADROP.pptx)
- [Estado técnico da versão 3](V3_DESENVOLVIMENTO.md)

## Desenvolvimento

Para trabalhar no código, instale:

- Git;
- JDK 25 ou compatível;
- PowerShell 7;
- Inno Setup 6, necessário somente para gerar o instalador.

Comandos principais:

```powershell
.\build.ps1
.\build.ps1 -Test
.\build.ps1 -Test -Installer
```

O último comando compila o aplicativo, executa os testes e gera `output\MagaDropSetup.exe`.

Estrutura do repositório:

- `src/`: servidor HTTP e interface Java Swing;
- `web/`: interface para navegadores;
- `tests/`: testes integrados e servidor descartável de demonstração;
- `launcher/`: launcher do executável Windows;
- `MagaDrop.iss`: configuração do instalador;
- `APRESENTACAO_MAGADROP.txt`: roteiro atualizado da apresentação;
- `apresentacao/APRESENTACAO_MAGADROP.pptx`: apresentação editável.

`web/qrcode.min.js` é o QRCode.js 1.0.0, distribuído sob licença MIT. As demais licenças estão em `THIRD_PARTY_LICENSES.md`.

## Licença

O código do MagaDrop é distribuído sob a [licença MIT](LICENSE). Componentes de terceiros permanecem sujeitos às licenças listadas em [THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).
