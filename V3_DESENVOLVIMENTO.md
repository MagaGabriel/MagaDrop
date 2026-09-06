# MagaDrop v3 — desenvolvimento

## Objetivo

Transformar o MagaDrop em um servidor de arquivos familiar seguro dentro da rede local antes de permitir qualquer acesso remoto.

## Marco 1: fundação de contas e sessões

Implementado nesta branch:

- cadastro local versionado de usuários;
- perfis `ADMIN` e `MEMBER`;
- migração automática da senha da versão 2 para a conta `MAGA`;
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
3. É criada a conta `MAGA` com essa senha.
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
- exclusão permanente de contas de membro, com confirmação pelo nome e senha atual do administrador;
- área **Minha conta** para cada pessoa trocar a própria senha;
- área **Usuários** no navegador, visível somente para administradores;
- nova autenticação do administrador antes de cada ação sensível;
- revogação automática de sessões após troca de senha ou desativação;
- testes que comprovam que membros não acessam a administração.

Senhas atuais e hashes nunca são enviados pela API. O administrador pode somente definir uma nova senha. A conta administradora principal não pode ser desativada nem excluída. Como ainda não existem pastas pessoais, a exclusão atual remove somente a conta; o tratamento dos arquivos será definido junto ao próximo marco.

## Marco 3: armazenamento e arquivos

Implementado:

- raiz pessoal exclusiva por identificador interno de usuário;
- raiz compartilhada baseada na pasta escolhida no aplicativo Windows;
- seleção visual do destino antes de enviar arquivos;
- navegação e listagem de pastas sem expor caminhos absolutos do Windows;
- criação segura de subpastas;
- download de arquivos pessoais e compartilhados;
- exclusão recuperável, movendo itens para uma lixeira interna;
- bloqueio de travessia de diretório, links simbólicos e junções;
- testes de isolamento entre contas e de todas as operações de arquivo.

A pasta principal escolhida no aplicativo contém `Compartilhada` e `Usuarios`. Neste computador, o padrão é `D:\backup nuvem`, portanto os caminhos são `D:\backup nuvem\Compartilhada` e `D:\backup nuvem\Usuarios`. A proteção é aplicada pelo servidor do MagaDrop; contas do próprio Windows com acesso ao disco continuam sujeitas às permissões do sistema operacional.

## Próximo marco

1. Tela de lixeira com restauração de arquivos.
2. Registro persistente de auditoria.
3. Dispositivos autorizados e HTTPS.
4. Acesso externo sem abertura direta da porta do roteador.

## Limite atual

Esta branch continua destinada exclusivamente a redes locais confiáveis. O cookie ainda não usa o atributo `Secure` porque o servidor local opera por HTTP. HTTPS, dispositivos autorizados e acesso externo pertencem a um marco posterior, depois que usuários e permissões estiverem estabilizados.
