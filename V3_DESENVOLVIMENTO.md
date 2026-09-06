# MagaDrop 3.0.0

## Objetivo

Transformar o MagaDrop em um servidor de arquivos familiar seguro dentro da rede local antes de habilitar acesso remoto.

## Contas e sessões

Implementado:

- conta administradora principal `MAGA` e contas de membros;
- cadastro local em `%LOCALAPPDATA%\MagaDrop\users.properties`;
- senhas protegidas com PBKDF2-HMAC-SHA-256, salt aleatório e 600 mil iterações;
- migração automática da senha antiga para a conta `MAGA`;
- sessões aleatórias de 256 bits mantidas somente na memória;
- cookies `HttpOnly` e `SameSite=Strict`;
- token CSRF nas operações de escrita;
- expiração das sessões por inatividade e tempo máximo;
- limitação de tentativas de login;
- revogação de sessões após alteração de senha ou desativação da conta.

O arquivo de usuários contém identificadores, perfis e hashes. O MagaDrop não armazena a senha original.

## Administração de usuários

O administrador pode usar o aplicativo Windows ou a área **Usuários** no navegador para:

- criar uma conta de membro e sua pasta pessoal;
- redefinir a senha de um membro;
- ativar ou desativar uma conta;
- encerrar as sessões de um membro;
- excluir uma conta mediante confirmação e senha administrativa.

A conta `MAGA` não pode ser desativada nem excluída. As ações sensíveis exigem novamente a senha do administrador. A exclusão da conta não apaga automaticamente a pasta pessoal, evitando perda acidental de arquivos.

## Armazenamento e arquivos

Na primeira instalação, a pessoa precisa selecionar explicitamente uma pasta base. O MagaDrop cria dentro dela:

```text
Pasta base
├── Compartilhada
└── Usuarios
    ├── maga-identificador
    └── usuario-identificador
```

Implementado:

- área pessoal exclusiva por identificador interno;
- área compartilhada acessível às contas autorizadas;
- escolha do destino antes do envio;
- navegação sem expor caminhos absolutos do computador;
- criação de subpastas;
- downloads para celular ou outro computador;
- exclusão recuperável por meio de uma lixeira interna;
- bloqueio de travessia de diretórios, links simbólicos e junções.

Neste computador, a pasta base é `D:\backup nuvem`. Essa escolha pertence à instalação local e não é incluída no instalador.

## Aplicativo Windows

- nome exibido, atalho e entrada do instalador: `MagaDrop`;
- servidor local iniciado preferencialmente na porta 8080;
- seleção automática de outra porta quando necessário;
- QR code e endereço da rede exibidos na janela;
- execução contínua pela bandeja do sistema;
- opção de início automático com o Windows;
- Java Runtime incluído no instalador.

## Testes automatizados

O teste integrado cobre autenticação, usuários, sessões, limite de tentativas, QR code, porta alternativa, isolamento das pastas, criação, download, upload e exclusão segura. A primeira configuração também possui teste para impedir avanço sem escolha explícita da pasta base.

## Limite atual

O MagaDrop opera por HTTP e foi projetado somente para redes locais confiáveis. O cookie ainda não usa `Secure` porque não há HTTPS. A porta do aplicativo não deve ser redirecionada diretamente no roteador.

## Próximos marcos

1. Tela de lixeira com restauração de arquivos.
2. Registro persistente de auditoria.
3. HTTPS e autorização de dispositivos.
4. Acesso externo sem exposição direta da porta do computador.
