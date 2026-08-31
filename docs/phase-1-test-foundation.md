# Fase 1 — Fundação de testes automatizados

Data de conclusão: 2026-08-30

## Objetivo

Criar uma rede mínima de proteção contra regressões antes das refatorações estruturais das fases seguintes, sem alterar deliberadamente o comportamento funcional do T4A Control.

## Cobertura implementada

### Dashboard

`T4ADashboardMapperTest` cobre:

- estado conectado e desconectado;
- velocidade;
- unidade km/h e mph;
- bateria e mínimo/máximo observado;
- modo de condução;
- lock;
- farol;
- partida;
- cruzeiro;
- auto-lock;
- RSSI;
- odômetro total e percurso;
- tempo de uso;
- pending DPS e habilitação dos controles;
- valores ausentes ou inválidos com defaults seguros.

### MQTT

`MqttConfigurationTest` cobre:

- TCP;
- TLS;
- WS;
- WSS;
- URI do servidor;
- host, porta e path;
- client id e base topic;
- keep alive;
- normalização;
- restauração de transport salvo e compatibilidade com configuração TLS legada.

### Home Assistant discovery

`HomeAssistantDiscoveryTest` cobre:

- normalização do MAC;
- identificador estável;
- tópico de discovery;
- state e availability topics;
- componentes esperados;
- tópico de localização;
- ausência de discovery quando não existe identificador suficiente;
- não fabricação de um MAC canônico a partir de identificador inválido.

### Backend

`T4ABackendTest` usa fakes de `T4AProvisioner` e `T4ATransport` e cobre:

- ausência de sessão restaurada;
- restauração de sessão;
- attach e conexão através das portas neutras;
- descoberta;
- pareamento;
- reconexão;
- descarte de `INITIAL/cache` para lock, velocidade e bateria;
- aceitação de RX live posterior;
- pending DPS;
- confirmação de comando por RX;
- semântica especial do lock;
- descarte de estado antigo do lock enquanto aguarda confirmação;
- auto-lock por RSSI;
- mínimo/máximo observado da bateria;
- detecção de candidato a recarga;
- confirmação de novo ciclo;
- rejeição de novo ciclo preservando o ciclo atual.

## Infraestrutura de testes

Foram adicionados:

- JUnit 4.13.2 nos módulos que possuem testes;
- Robolectric 4.16 para os testes do backend que ainda dependem de APIs Android;
- configuração de JVM necessária para Robolectric em Java 17+;
- job `Unit tests` no workflow `Architecture boundary`;
- execução automática de:

```text
:backend:testDebugUnitTest
:app:testDebugUnitTest
```

O job restaura os mesmos insumos privados Tuya utilizados pelo workflow oficial de build, a partir do bundle criptografado e dos GitHub Secrets existentes. Nenhum AAR privado ou segredo foi adicionado ao repositório.

## Resultado de CI

No encerramento desta fase, o workflow passou com sucesso nos três jobs:

- `Verify version branch`;
- `Verify Tuya boundary`;
- `Unit tests`.

A suíte passou após validar o comportamento real do lock; o teste foi ajustado para refletir a sequência de emissão de estado já existente, sem alteração do código de produção.

## Caso `RX=100` de bateria

O plano original menciona impedir que um `RX=100` espúrio durante descarga recupere artificialmente o SOC. A implementação atual não possui fonte de tempo injetável e não há critério seguro, apenas pelo valor recebido, para distinguir um `100%` espúrio de um `100%` legítimo após carga.

Esta fase não introduz uma heurística nova de produção apenas para satisfazer um teste. O caso fica explicitamente ligado à Fase 4 (`Clock`/`Scheduler`) e aos logs de campo, quando será possível testar tempo e sequência de leituras de forma determinística.

## Critério de saída

A fundação de testes está apta a proteger as próximas refatorações. Qualquer alteração das fases seguintes deve manter os testes existentes verdes e adicionar cobertura quando modificar uma regra já protegida.
