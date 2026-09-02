# Fase 6 — preparação do domínio de navegação

## Primeiro corte

Este corte cria o domínio neutro de navegação sem alterar o controle físico do T4A, a sessão BLE/Tuya, MQTT, persistência ou UI.

Foram introduzidos em `backend/navigation`:

- `Route` — rota imutável e independente do provedor;
- `Waypoint` — ponto ordenado com papel explícito de origem, passagem ou destino;
- `RouteLeg` — trecho entre dois waypoints;
- `NavigationInstruction` — instrução neutra de manobra;
- `NavigationState` — estado atual da navegação;
- `RouteParser` — contrato para futuros importadores de rotas externas;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya ou MQTT.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino; um waypoint intermediário não é tratado como destino final.
- Persistência e UI permanecem para fases posteriores, conforme o plano de endurecimento arquitetural.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

Isso confirma que o contrato de entrada do aplicativo precisa aceitar short links `maps.app.goo.gl`; eles não contêm diretamente a estrutura da rota. A expansão do short link é uma responsabilidade de infraestrutura e não pertence ao parser neutro.

Foi introduzido `RouteReferenceResolver` no backend como fronteira neutra e `GoogleMapsRouteReferenceResolver` no módulo Android como adaptador HTTP. O adaptador apenas expande redirecionamentos do short link e devolve a referência final. `RouteParser` continua responsável exclusivamente por converter a referência já resolvida em `Route`.

Fluxo atualizado:

```text
URL compartilhada
  -> RouteReferenceResolver (infraestrutura)
  -> referência expandida
  -> RouteParser (formato da rota)
  -> Route
  -> NavigationEngine
  <- LocationSnapshot
  -> NavigationState
```

Nenhum SDK Google foi introduzido.

## Critério coberto

Os testes verificam que:

- a rota preserva a ordem e o papel dos waypoints;
- `NavigationEngine` recebe `LocationSnapshot` diretamente;
- uma instrução corrente pode ser produzida sem conhecimento do provedor;
- atingir uma instrução avança para a próxima;
- ausência de localização é um estado explícito;
- referências já expandidas passam pelo resolver sem modificação;
- referência vazia é rejeitada antes de qualquer acesso de rede.

## Próximo corte

Capturar a URL final resultante do redirecionamento `maps.app.goo.gl` no Android real ou em um ambiente capaz de seguir o short link. Essa URL expandida determinará a implementação concreta do primeiro `RouteParser` sem inferir um formato que ainda não foi observado.
