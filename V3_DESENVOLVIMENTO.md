# MagaDrop v3 — desenvolvimento

## Objetivo

Transformar o MagaDrop em um servidor de arquivos familiar seguro dentro da rede local antes de permitir qualquer acesso remoto.

## Marco 1: fundação de contas e sessões

Implementado nesta branch:

- cadastro local versionado de usuários;
- perfis `ADMIN` e `MEMBER`;
- migração automática da senha da versão 2 para a conta `admin`;
- senhas protegidas com PBKDF2-HMAC-SHA-256, salt aleatório e 600 mil iterações;
- sessões aleatórias de 256 bits, mantidas somente na memória do servidor;
- cookies `HttpOnly` e `SameSite=Strict`;
- token CSRF exigido em uploads e logout;
- expiração por inatividade e por tempo máximo;
- revogação de todas as sessões quando a senha do administrador muda;
- limitação de tentativas de login por usuário e endereço de origem;
- login e logout na interface web;
- upload permitido somente após autenticação;
- testes integrados do fluxo completo.

O cadastro fica em `%LOCALAPPDATA%\MagaDrop\users.properties` e contém apenas identificadores, perfis e hashes de senha. A senha original não é armazenada. O formato possui versão para permitir migração futura para SQLite quando o catálogo, as permissões e o histórico de arquivos forem introduzidos.

## Comportamento ao atualizar da versão 2

Na primeira abertura da versão 3:

1. A pasta de uploads continua sendo utilizada.
2. A senha configurada na versão 2 é transformada em hash.
3. É criada a conta `admin` com essa senha.
4. A senha legível é removida das preferências antigas.

Se for uma instalação nova, a configuração inicial exige uma senha ou frase-senha entre 10 e 128 caracteres.

## Marco 2: administração de usuários

Implementado:

- botão **Usuários** no aplicativo Windows;
- tabela local com perfil, estado e quantidade de sessões;
- criação de contas de membro;
- redefinição segura da senha de membros;
- ativação e desativação de contas;
- encerramento remoto das sessões de um membro;
- área **Minha conta** para cada pessoa trocar a própria senha;
- área **Usuários** no navegador, visível somente para administradores;
- nova autenticação do administrador antes de cada ação sensível;
- revogação automática de sessões após troca de senha ou desativação;
- testes que comprovam que membros não acessam a administração.

Senhas atuais e hashes nunca são enviados pela API. O administrador pode somente definir uma nova senha.

## Próximo marco

1. Raízes de armazenamento `Pessoal` e `Compartilhados`.
2. Permissões verificadas no servidor em todas as operações.
3. Listagem de arquivos e criação segura de pastas.
4. Download por identificador interno, sem expor caminhos do Windows.

Excluir arquivos continuará desabilitado até existir lixeira, registro de auditoria e testes de recuperação.

## Limite atual

Esta branch continua destinada exclusivamente a redes locais confiáveis. O cookie ainda não usa o atributo `Secure` porque o servidor local opera por HTTP. HTTPS, dispositivos autorizados e acesso externo pertencem a um marco posterior, depois que usuários e permissões estiverem estabilizados.
