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
- `RoutePlanner` — fronteira neutra para obter geometria/instruções de um provedor externo;
- `NavigationEngine` — avanço mínimo de instruções consumindo exclusivamente `LocationSnapshot`.

## Decisões arquiteturais

- Navegação permanece fora de `T4ABackend`.
- `LocationSnapshot` é a única entrada de posição; não existe segundo GPS.
- O núcleo não depende de Android, Google Maps, Tuya, MQTT ou de um motor específico de rotas.
- Waypoints preservam ordem e distinguem origem, pontos intermediários e destino; um waypoint intermediário não é tratado como destino final.
- Um waypoint importado pode existir temporariamente sem coordenadas quando a URL fornece apenas endereço/label; latitude e longitude devem estar ambas presentes ou ambas ausentes.
- Importar a intenção da rota e calcular as instruções são responsabilidades separadas.
- Persistência e UI permanecem para cortes posteriores.

## Evidência real do compartilhamento Google Maps

A primeira rota fornecida para a Fase 6 foi compartilhada como short link:

`https://maps.app.goo.gl/LGtVxynCV4YpAZze7?g_st=ac`

A URL expandida observada foi uma rota `/maps/dir/` contendo, em ordem:

1. origem em coordenadas `-23.6377584,-46.658869`;
2. waypoint intermediário em coordenadas `-23.6133508,-46.6884184`;
3. destino como endereço `Av. Brig. Faria Lima, 4400 - Itaim Bibi, São Paulo - SP, 04538-132, Brasil`.

Fluxo:

```text
URL compartilhada maps.app.goo.gl
  -> RouteReferenceResolver
  -> URL expandida /maps/dir/...
  -> GoogleMapsRouteParser
  -> Route neutra com waypoints
  -> RoutePlanner
  -> Google Routes API
  -> Route neutra com RouteLeg/NavigationInstruction
  -> NavigationEngine
  <- LocationSnapshot
  -> NavigationState
```

`GoogleMapsRouteReferenceResolver`, `GoogleMapsRouteParser` e `GoogleRoutesRoutePlanner` ficam no módulo Android. Nenhuma dependência Google entra no backend neutro.

## Google Routes API

O primeiro `RoutePlanner` concreto usa `POST https://routes.googleapis.com/directions/v2:computeRoutes`.

A solicitação usa:

- `travelMode = TWO_WHEELER`;
- `languageCode = pt-BR`;
- `units = METRIC`;
- origem/destino/intermediários na ordem importada;
- coordenadas quando presentes;
- endereço textual quando a URL não fornece coordenadas;
- field mask limitada a `routes.legs.steps.endLocation` e `routes.legs.steps.navigationInstruction`.

Cada step retornado vira uma `NavigationInstruction`; a coordenada de `endLocation` é usada como ponto de avanço da instrução.

A chave não é versionada. O build aceita `GOOGLE_ROUTES_API_KEY` por variável de ambiente ou por `maps.properties`, que é ignorado pelo Git. `maps.properties.example` documenta a configuração.

Para chamadas REST diretas do Android, `GoogleRoutesPlannerFactory` calcula o SHA-1 do certificado que assinou o APK e o adaptador envia `X-Android-Package` e `X-Android-Cert`, permitindo restringir a chave no Google Cloud ao aplicativo Android e à Routes API.

## Critério coberto

Os testes existentes verificam que:

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

## Próxima validação

Para validar o provedor real é necessário habilitar a Routes API em um projeto Google Cloud com billing ativo, criar uma chave dedicada, restringi-la à Routes API e ao pacote Android `br.com.t4acontrol` com o SHA-1 do certificado usado para assinar o APK de teste, e fornecer a chave localmente em `maps.properties` ou `GOOGLE_ROUTES_API_KEY`.

A primeira execução real deve usar a rota já registrada acima e verificar:

1. retorno de dois legs para os três waypoints;
2. instruções em português;
3. manobras coerentes com o Google Maps;
4. avanço do `NavigationEngine` com `LocationSnapshot`;
5. ausência de alteração no controle BLE/Tuya do T4A.

Rotas `TWO_WHEELER` estão em beta; o aviso exigido pelo Google deverá ser exibido quando a navegação chegar à UI.
