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
- `RoutePlanner` — fronteira neutra para enriquecer uma rota importada com geometria e instruções;
- `RoutingProfile` — preferência escolhida pelo usuário (`BICYCLE`, `FOOT`, `CAR`), sem vínculo com o modo físico do T4A;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya, MQTT ou de um motor específico de rotas.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino.
- Um waypoint importado pode existir temporariamente sem coordenadas quando a URL fornece apenas endereço/label.
- Importar uma rota e planejá-la são operações distintas.
- O projeto não dependerá de chave Google para navegação.
- O perfil de roteamento é preferência do usuário, não limitação imposta pelo veículo.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como short link:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

A URL expandida observada contém, em ordem:

1. origem em coordenadas `-23.6377584,-46.658869`;
2. waypoint intermediário em coordenadas `-23.6133508,-46.6884184`;
3. destino como endereço `Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil`.

## Planejamento sem chave — decisão atual

Valhalla foi experimentado e descartado porque não conseguiu calcular adequadamente a rota real usada como caso de validação da Fase 6.

OSRM passa a ser o candidato atual porque o perfil de bicicleta conseguiu reproduzir adequadamente o percurso observado. O adapter usa os grafos separados oferecidos por `routing.openstreetmap.de`:

- `routed-bike` para `BICYCLE`;
- `routed-foot` para `FOOT`;
- `routed-car` para `CAR`.

O trecho `/route/v1/driving/` permanece na URL desses endpoints porque o perfil efetivo já está determinado pelo grafo preparado no servidor. O aplicativo não deve interpretar `driving` como modo carro nesse caso.

A rota escolhida pelo usuário pode ser recalculada em qualquer um dos três perfis. `BICYCLE` pode ser apresentado inicialmente como opção conveniente para o T4A, mas não deve bloquear nem esconder `FOOT` ou `CAR`.

Quando um waypoint importado não contém coordenadas, Nominatim é usado apenas para resolver o endereço textual antes do planejamento.

Fluxo atual:

```text
Google Maps compartilhado
  -> RouteReferenceResolver
  -> GoogleMapsRouteParser
  -> Route neutra
  -> escolha do RoutingProfile pelo usuário
  -> Nominatim para coordenadas faltantes
  -> OsrmRoutePlanner
       -> routed-bike | routed-foot | routed-car
  -> NavigationInstruction
  -> NavigationEngine
  <- LocationSnapshot
```

## Limites atuais

- O percurso é recalculado a partir dos waypoints; não há promessa de reprodução da geometria interna opaca do Google Maps.
- Servidores públicos são adequados para desenvolvimento e uso leve, mas permanecem substituíveis por infraestrutura própria.
- Persistência, UI de seleção de perfil, detecção de saída da rota e recálculo continuam fora deste corte.
