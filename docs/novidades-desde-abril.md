# Novidades — a partir de 16/04/2026

---

## Upgrade de Container

- **Trocar a tag de imagem** de um container sem perder a configuração — o container antigo é parado, removido e recriado com a nova imagem, mantendo nome, variáveis de ambiente, memória, portas, expiração e agendamentos
- Opcionalmente **executar migração de banco** como parte do upgrade
- Seletor de tags ordenado por versão (mais recentes primeiro)
- Reutilização de portas: tenta manter as mesmas portas do host, busca novas se indisponíveis
- Suporte a **modo somente migração** (executar SQL sem trocar a tag)
- Sincronização automática da versão alvo da migração ao selecionar uma nova tag
- Habilitado por repositório via `repository.upgrade-enabled.<repo>=true`

---

## Bancos de Dados Gerenciados

Nova aba "Bancos de Dados" na página de banco, para visualizar e gerenciar bancos PostgreSQL em tempo real:

- **Listagem com métricas ao vivo** — nome, tamanho, conexões ativas, tempo ocioso, flag de proteção
- **Filtros** — busca por nome, toggle "com conexões", filtro por tempo ocioso (> 7, 14, 30, 60 dias, nunca usado)
- **Flag de proteção** — marcar bancos como protegidos para impedir exclusão acidental (exige senha de operações)
- **Exclusão individual e em lote** — drop de bancos com confirmação e terminação de conexões ativas
- **Limpeza por tempo ocioso** — slider para definir dias mínimos, preview ao vivo dos bancos afetados
- **Menu de contexto (clique direito)** — ações rápidas: proteger, deletar, criar snapshot, restaurar dump, executar migração
- **Descrição editável** — adicionar descrição a qualquer banco para identificação
- **Card resumo** — total de bancos, tamanho total e quantidade protegidos
- **Metadados compartilhados** — repositórios que apontam para o mesmo servidor PostgreSQL (mesmo `pg-host` e `pg-port`) compartilham proteção, criador, tenant e descrição de cada banco; proteger em uma aba protege em todas

---

## Insights de Banco de Dados

Clicar no nome de um banco abre o diálogo de insights com métricas detalhadas:

### Visão de Saúde
- Tamanho do banco, cache hit ratio, conexões ativas e em espera
- Queries de longa duração, dead tuples, idade do transaction ID
- Commits e rollbacks acumulados, uso de arquivos temporários

### Monitoramento de Atividade
- **Sessões ativas** (top 20) — usuário, estado, query, IP do cliente, duração, tipo de espera
- **Top usuários por conexões** — breakdown de conexões ativas, ociosas e totais por usuário
- **Processos bloqueados** (top 20) — detecção de deadlocks com PIDs bloqueando/bloqueados e duração da espera

### Top Queries (pg_stat_statements)
- Requer extensão `pg_stat_statements` — botão para habilitar com um clique
- Queries normalizadas com tempo de execução (total e médio), contagem de chamadas e linhas retornadas
- I/O de arquivos temporários por query — identifica queries com pressão de memória

### Queries com Arquivos Temporários
- Visão dedicada de queries que geram temp files, ordenadas por tamanho
- Identifica queries que excedem o `work_mem` e escrevem resultados intermediários em disco

### Estatísticas de Tabelas e Indexes
- Tamanho (dados vs indexes), varreduras sequenciais vs por index, dead rows, timestamps de vacuum
- **IDX USAGE** — porcentagem de scans que usaram index (valores baixos indicam indexes faltando)
- **Indexes não utilizados** — indexes com `idx_scan = 0` (excluindo PKs e unique) com espaço desperdiçado
- **Impacto de indexes** — análise por tabela mostrando overhead de escrita estimado (~2-5% por index não utilizado)

### Executor de Queries SQL
- **Modo somente leitura** (padrão) — apenas SELECT permitido
- **Modo escrita** (configurável) — INSERT, UPDATE, DELETE também permitidos
- **EXPLAIN / ANALYZE** — ver o plano de execução de qualquer query
- Paginação de resultados com tamanho máximo de página configurável
- Timeout de execução configurável (padrão 30 segundos)

### Reset de Estatísticas
- **Reset Query Stats** (aba Top Queries) — chama `pg_stat_statements_reset()` para o banco atual
- **Reset Table Stats** (aba Tabelas) — chama `pg_stat_reset()` para o banco atual
- **Reset por tabela** (botão individual) — chama `pg_stat_reset_single_table_counters()` para uma tabela específica
- Todos os resets são protegidos por senha e restritos ao banco atual

### Relatório HTML
- Gerar relatório HTML autocontido com todas as métricas de insights
- Inclui: saúde, atividade, tabelas, queries temporárias, saúde do servidor (versão, uptime, limites)
- Ideal para compartilhamento offline com equipes

---

## Integração Container-Banco de Dados

- **Indicadores de banco** nos containers — visualizar qual banco cada container utiliza
- **Editar expiração** após criação — alterar o tempo de expiração ou adicionar expiração a qualquer momento
- **Habilitar deleção de banco** após criação — ativar a exclusão automática do banco na expiração do container, com a mesma validação e confirmação do fluxo de criação
- **Hint de versão incompatível** — alerta informativo sugerindo migração quando a versão do dump é anterior à tag selecionada

---

## Melhorias no Restore de Dumps

- **Limpeza de schema** antes do restore — ao restaurar em um banco existente, o schema atual é limpo automaticamente antes do restore, evitando conflitos

---

## Preview de Limpeza

- Antes de executar limpeza de bancos ociosos ou prune de imagens, um **preview** mostra exatamente o que será afetado
- Permite revisar antes de confirmar a operação

---

## Analisador de Logs — Novidades

### Detecção de Requisições Duplicadas
- Identificar requisições idênticas repetidas que podem indicar retries do cliente, loops travados, etc.
- Agrupamento por endpoint e conteúdo do payload
- Contagem de ocorrências com período de tempo
- Tabela paginada com detalhes expansíveis
- Ajuda a indentificar possíveis oportunidades de cache

### Filtros Avançados de Chamadas de API
- **Filtro de exclusão** — excluir chamadas que contenham determinados padrões
- **Busca multi-padrão** — combinar múltiplos termos de busca
- **Matching de whitespace em JSON** — busca inteligente que ignora diferenças de formatação

### Truncamento de Payload
- Payloads grandes são truncados na listagem (limite configurável, padrão 100KB)
- **Download completo** disponível para payloads que excedem o limite

### Rastreamento de Jobs Órfãos
- Jobs que iniciaram mas não tiveram fim detectado são rastreados separadamente
- Útil para identificar jobs que travaram ou crasharam

### Ordenação por Coluna na Tabela de Jobs
- Ordenar jobs clicando nos cabeçalhos das colunas (nome, duração, resultado)
- Filtro por status do job

### Melhorias no Log Bruto
- **Filtro de exclusão** — excluir linhas que contenham determinados padrões
- **Destaque de palavra** ao dar duplo clique em qualquer palavra no log
- **Navegação View API Calls** — ir diretamente do Performance Insights para as chamadas de API correspondentes

### Suporte a Arquivos Não-UTF-8
- Arquivos com encoding diferente de UTF-8 são processados sem crash, usando caractere de substituição para bytes inválidos

---

## Remoção de Container com Exclusão de Banco

- Ao remover um container que possui banco de dados vinculado, opção de **excluir o banco junto** com o container
- **Proteção respeitada** — bancos marcados como protegidos não são excluídos, com aviso ao usuário
- **Aviso de containers dependentes** — alerta quando outros containers estão usando o mesmo banco, listando quais serão afetados
- Operação protegida por senha de operações

---

## Migração — de branchs DEV

- **Extração de branch e versão** da tag de imagem via regex configurável por repositório
- Placeholder `{branch}` disponível na URL da API de migração, além de `{sourceVersion}` e `{targetVersion}`

---

## Registries — Mapeamento de Path e Autenticação V2

- **Mapeamento de path por repositório** — mapear nomes curtos de repositório para caminhos completos em registries com estrutura de grupos aninhados (ex: GitLab `mygroup/myproject/myapp`)
- **Autenticação V2 com Bearer token** — suporte a troca de tokens via header `Www-Authenticate` para GitLab, GitHub GHCR e outros registries OCI
- **Suporte a paths no Docker Hub** — mapear repositórios para caminhos customizados no Docker Hub (ex: `sparkinfra/mywms_spk_qa`)
- **Proteção SSRF** — valida que o endpoint de token pertence ao mesmo domínio do registry, prevenindo redirecionamento malicioso
- **Segurança em respostas JSON** — trata corretamente valores nulos em respostas de registries V2 (campo `tags` ausente, `tags: null`, elementos nulos)
- Configurável via `repository.registry-path.<repo>=caminho/completo`

---

## Terminal — Anexos de Imagem

- **Colar ou arrastar uma imagem no terminal** (Ctrl+V, ou ⌘V no macOS) — a imagem é enviada para dentro do container e o caminho do arquivo é digitado no prompt, pronto para um LLM ou qualquer ferramenta rodando no terminal
- **Modelo do caminho** — texto digitado após o envio, configurável no painel de Configurações; `"{path}"` coloca o caminho entre aspas (útil quando a ferramenta interpreta `/` como comando) e vazio (ou `none` na propriedade) não digita nada
- **Validação pelo conteúdo** — o servidor identifica PNG, JPEG, GIF e WebP pelos bytes iniciais, ignora o nome enviado e gera um nome sem colisão
- **Flags próprias** — `container.terminal.upload.image.enabled` e `container.terminal.upload.image.path` (padrão `/tmp`), independentes do envio de arquivos genérico, editáveis em tempo de execução
- **Auditoria** — envios de arquivos e imagens pelo terminal geram entradas `TERMINAL_UPLOAD` e `TERMINAL_IMAGE_UPLOAD` com usuário, container, arquivo e tamanho
- Fechar o diálogo cancela o envio em andamento; texto colado continua indo direto para o shell

---

## Configurações em Tempo de Execução — Categorias e Dependências

- Aba de Configurações agrupada por categoria (terminal, analisador de logs, sessões, auditoria)
- Configurações dependentes aparecem aninhadas sob a flag que as habilita; enquanto a flag está desligada a linha fica esmaecida com um aviso, mas continua editável
- Retenção de auditoria avisa quando `audit.enabled=false` (propriedade que exige reinício)
- Configurações de texto (caminhos, modelos) com validação na própria tela

---

## Bancos Gerenciados — Metadados Compartilhados entre Repositórios

- Repositórios com o mesmo `pg-host` e `pg-port` passam a compartilhar um único registro de metadados por banco: proteção, criador, tenant, descrição, último restore e último uso
- Um banco protegido em uma aba não pode ser excluído por outra aba do mesmo servidor
- **Mesclagem única na inicialização** dos registros duplicados criados antes desta versão (vence o registro que conhece o criador; a proteção é mantida se qualquer lado a tinha); resumo no log e chave de desligamento `database.managed.sibling-merge-at-startup`
- Recomenda-se backup da tabela `managed_database` (ou `data/managed-databases.json`) antes da primeira inicialização

---
