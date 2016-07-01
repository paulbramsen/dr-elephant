# --- Created by Ebean DDL
# To stop Ebean DDL generation, remove this comment and start using Evolutions

# --- !Ups

create table yarn_app_heuristic_result (
  id                        integer not null,
  yarn_app_result_id        varchar(50),
  heuristic_class           varchar(255) not null,
  heuristic_name            varchar(128) not null,
  severity                  integer not null,
  score                     integer not null,
  constraint ck_yarn_app_heuristic_result_severity check (severity in ('3','4','2','0','1','5')),
  constraint pk_yarn_app_heuristic_result primary key (id))
;

create table yarn_app_heuristic_result_details (
  yarn_app_heuristic_result_id integer,
  name                      varchar(128) not null,
  value                     varchar(255) not null,
  details                   varchar(255))
;

create table yarn_app_result (
  id                        varchar(50) not null,
  name                      varchar(100) not null,
  username                  varchar(50) not null,
  queue_name                varchar(50) not null,
  start_time                bigint not null,
  finish_time               bigint not null,
  tracking_url              varchar(255) not null,
  job_type                  varchar(20) not null,
  severity                  integer not null,
  score                     integer not null,
  workflow_depth            integer not null,
  scheduler                 varchar(20),
  job_name                  varchar(255) not null,
  job_exec_id               varchar(800) not null,
  flow_exec_id              varchar(255) not null,
  job_def_id                varchar(800) not null,
  flow_def_id               varchar(800) not null,
  job_exec_url              varchar(800) not null,
  flow_exec_url             varchar(800) not null,
  job_def_url               varchar(800) not null,
  flow_def_url              varchar(800) not null,
  resource_used             bigint,
  resource_wasted           bigint,
  total_delay               bigint,
  status                    varchar(10) not null,
  constraint ck_yarn_app_result_severity check (severity in ('3','4','2','0','1','5')),
  constraint pk_yarn_app_result primary key (id))
;

create sequence yarn_app_heuristic_result_seq;

create sequence yarn_app_result_seq;

alter table yarn_app_heuristic_result add constraint fk_yarn_app_heuristic_result_y_1 foreign key (yarn_app_result_id) references yarn_app_result (id) on delete restrict on update restrict;
create index ix_yarn_app_heuristic_result_y_1 on yarn_app_heuristic_result (yarn_app_result_id);
alter table yarn_app_heuristic_result_details add constraint fk_yarn_app_heuristic_result_d_2 foreign key (yarn_app_heuristic_result_id) references yarn_app_heuristic_result (id) on delete restrict on update restrict;
create index ix_yarn_app_heuristic_result_d_2 on yarn_app_heuristic_result_details (yarn_app_heuristic_result_id);



# --- !Downs

SET REFERENTIAL_INTEGRITY FALSE;

drop table if exists yarn_app_heuristic_result;

drop table if exists yarn_app_heuristic_result_details;

drop table if exists yarn_app_result;

SET REFERENTIAL_INTEGRITY TRUE;

drop sequence if exists yarn_app_heuristic_result_seq;

drop sequence if exists yarn_app_result_seq;

