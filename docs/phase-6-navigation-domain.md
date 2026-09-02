# Fase 6 — preparação do domínio de navegação

## Primeiro corte

Este corte cria o domínio neutro de navegação sem alterar o controle físico do T4A, a sessão BLE/Tuya, MQTT ou a UI.

Foram introduzidos em `backend/navigation`:

- `Route` — rota imutável e independente do provedor;
- `Waypoint` — ponto ordenado com papel explícito de origem, passagem ou destino;
- `RouteLeg` — trecho entre dois waypoints, incluindo geometria neutra quando planejada;
- `GeoPoint` — ponto da geometria da rota;
- `NavigationInstruction` — instrução neutra de manobra, incluindo distância do segmento e offset ao longo da rota;
- `NavigationState` — estado atual da navegação;
- `RouteParser` — contrato para importadores de rotas externas;
- `RouteReferenceResolver` — fronteira neutra para resolver referências externas antes do parsing;
- `RoutePlanner` — fronteira neutra para enriquecer uma rota importada com geometria e instruções;
- `RouteRecalculator` — reconstrói apenas o percurso restante a partir da posição atual após `OFF_ROUTE`;
- `RouteReferenceStore` — fronteira mínima de persistência da referência externa canônica;
- `RoutingProfile` — perfil importado da rota (`BICYCLE`, `FOOT`, `CAR`), sem vínculo com o modo físico do T4A;
- `NavigationEngine` — progresso de navegação consumindo exclusivamente `LocationSnapshot`, projetando a posição sobre a geometria e detectando desvio da rota.

No módulo Android, `SharedPreferencesRouteReferenceStore` persiste somente a referência original compartilhada. Geometria e instruções derivadas do OSRM não são persistidas: podem ser reconstruídas a partir da fonte canônica.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya, MQTT ou de um motor específico de rotas.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino.
- Um waypoint importado pode existir temporariamente sem coordenadas quando a URL fornece apenas endereço/label.
- Importar uma rota e planejá-la são operações distintas.
- O projeto não dependerá de chave Google para navegação.
- O perfil de roteamento é intenção importada da rota compartilhada, não limitação imposta pelo veículo.
- Ao sair da rota, waypoints já cumpridos não voltam a fazer parte do percurso recalculado.
- A referência compartilhada é a informação persistente; geometria de provedor é cache derivável, não estado canônico.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como short link:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

A URL expandida observada contém, em ordem:

1. origem em coordenadas `-23.6377584,-46.658869`;
2. waypoint intermediário em coordenadas `-23.6133508,-46.6884184`;
3. destino como endereço `Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil`;
4. modo bicicleta no marcador compartilhado observado `!3e1`.

URLs oficiais com `travelmode=driving|bicycling|walking` são preferidas quando esse parâmetro estiver disponível. O bloco `!3eX` é apenas fallback de compatibilidade testado, por não ser uma API pública documentada.

## Planejamento sem chave — decisão atual

Valhalla foi experimentado e descartado porque não conseguiu calcular adequadamente a rota real usada como caso de validação da Fase 6.

OSRM é o planejador atual porque o perfil de bicicleta conseguiu reproduzir adequadamente o percurso observado. O adapter usa os grafos separados oferecidos por `routing.openstreetmap.de`:

- `routed-bike` para `BICYCLE`;
- `routed-foot` para `FOOT`;
- `routed-car` para `CAR`.

O trecho `/route/v1/driving/` permanece na URL desses endpoints porque o perfil efetivo já está determinado pelo grafo preparado no servidor. O aplicativo não deve interpretar `driving` como modo carro nesse caso.

Quando um waypoint importado não contém coordenadas, Nominatim é usado apenas para resolver o endereço textual antes do planejamento.

O parser OSRM incorpora as `steps`, distâncias e geometrias GeoJSON. O `NavigationEngine` projeta a localização atual sobre a geometria do trecho para calcular a distância ao longo do percurso até a próxima instrução. Distância lateral superior ao limiar configurado produz `OFF_ROUTE`.

Quando ocorre `OFF_ROUTE`, `RouteRecalculator` cria uma nova solicitação contendo:

1. posição GPS atual como nova origem;
2. somente os waypoints ainda não cumpridos;
3. o mesmo `RoutingProfile` importado da rota original;
4. a referência original preservada.

Essa solicitação volta ao mesmo `RoutePlanner`, mantendo o núcleo independente de OSRM.

Fluxo atual:

```text
Google Maps compartilhado
  -> persistência da referência original
  -> RouteReferenceResolver
  -> GoogleMapsRouteParser
       -> RoutingProfile importado
  -> Route neutra
  -> Nominatim para coordenadas faltantes
  -> OsrmRoutePlanner
       -> routed-bike | routed-foot | routed-car
       -> steps + distância + geometria
  -> NavigationEngine
       <- LocationSnapshot
       -> NAVIGATING | ARRIVED | OFF_ROUTE
  -> OFF_ROUTE
       -> RouteRecalculator
       -> OsrmRoutePlanner
       -> nova rota somente com o percurso restante
```

## Limites atuais

- O percurso é recalculado a partir dos waypoints; não há promessa de reprodução da geometria interna opaca do Google Maps.
- Servidores públicos são adequados para desenvolvimento e uso leve, mas permanecem substituíveis por infraestrutura própria.
- O limiar inicial de `OFF_ROUTE` ainda precisa ser calibrado em teste real.
- O recálculo está implementado no domínio, mas ainda não está conectado ao ciclo de vida Android/UI.
- A persistência atual guarda a rota ativa pela referência compartilhada; catálogo/nomeação de vários percursos salvos pertence ao corte de UI/UX.
- A integração Android para receber `ACTION_SEND`/URLs compartilhadas e a apresentação visual das instruções ainda não fazem parte deste corte.
