# Fase 6 — preparação do domínio de navegação

## Primeiro corte

Este corte cria o domínio neutro de navegação sem alterar o controle físico do T4A, a sessão BLE/Tuya, MQTT, persistência ou UI.

Foram introduzidos em `backend/navigation`:

- `Route` — rota imutável e independente do provedor;
- `Waypoint` — ponto ordenado com papel explícito de origem, passagem ou destino;
- `RouteLeg` — trecho entre dois waypoints;
- `NavigationInstruction` — instrução neutra de manobra;
- `NavigationState` — estado atual da navegação;
- `RouteParser` — contrato para importadores de rotas externas;
- `RouteReferenceResolver` — fronteira neutra para resolver referências externas antes do parsing;
- `RoutePlanner` — fronteira neutra para enriquecer uma rota importada com geometria e instruções fornecidas por um motor externo;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya, MQTT ou de um motor específico de rotas.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino; um waypoint intermediário não é tratado como destino final.
- Um waypoint importado pode existir temporariamente sem coordenadas quando a URL fornece apenas endereço/label; latitude e longitude devem estar ambas presentes ou ambas ausentes.
- Importar uma rota e planejá-la são operações distintas: `RouteParser` recupera a intenção/ordem dos pontos; `RoutePlanner` pode posteriormente resolver coordenadas, geometria e instruções.
- Persistência e UI permanecem para fases posteriores, conforme o plano de endurecimento arquitetural.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como short link:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

A URL expandida observada foi uma rota `/maps/dir/` contendo, em ordem:

1. origem em coordenadas `-23.6377584,-46.658869`;
2. waypoint intermediário em coordenadas `-23.6133508,-46.6884184`;
3. destino como endereço `Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil`.

Isso confirma responsabilidades separadas:

```text
URL compartilhada maps.app.goo.gl
  -> RouteReferenceResolver (infraestrutura HTTP)
  -> URL expandida /maps/dir/...
  -> GoogleMapsRouteParser
  -> Route importada e neutra
  -> RoutePlanner (motor externo escolhido)
  -> Route com geometria/instruções
  -> NavigationEngine
  <- LocationSnapshot
  -> NavigationState
```

`GoogleMapsRouteReferenceResolver` fica no módulo Android e apenas expande redirecionamentos. `GoogleMapsRouteParser` também fica fora do núcleo neutro e interpreta o formato observado do Google Maps sem SDK Google.

O parser não interpreta o trecho `/@latitude,longitude,zoom` como waypoint; esse trecho representa viewport da URL. Também não depende dos parâmetros de tracking `utm_*`/`g_ep`.

## Critério coberto

Os testes verificam que:

- a rota preserva a ordem e o papel dos waypoints;
- `NavigationEngine` recebe `LocationSnapshot` diretamente;
- uma instrução corrente pode ser produzida sem conhecimento do provedor;
- atingir uma instrução avança para a próxima;
- ausência de localização é um estado explícito;
- referências já expandidas passam pelo resolver sem modificação;
- referência vazia é rejeitada antes de qualquer acesso de rede;
- a URL real fornecida na Fase 6 gera exatamente três waypoints na ordem origem -> passagem -> destino;
- origem e passagem preservam suas coordenadas;
- o destino textual permanece válido mesmo quando a URL não traz coordenadas explícitas para ele.

## Limite atual

A URL compartilhada fornece os pontos da rota, mas não fornece no caminho textual todas as instruções turn-by-turn nem coordenadas explícitas para todo endereço. Portanto o importador recupera corretamente a estrutura da rota, mas não fabrica manobras ou geometria inexistentes.

A escolha do motor de planejamento fica agora atrás de `RoutePlanner`. Google Routes API é uma opção compatível com a origem Google Maps, porém exige credencial e billing; outro motor pode ser usado sem alterar o núcleo. A próxima implementação concreta deve ocorrer no módulo de infraestrutura/app, nunca dentro de `backend/navigation`.
