CREATE DATABASE IF NOT EXISTS cqrs_jdbc_demo;
USE cqrs_jdbc_demo;

-- Create syntax for TABLE 'akka_projection_management'
CREATE TABLE `akka_projection_management` (
                                              `projection_name` varchar(255) NOT NULL,
                                              `projection_key` varchar(255) NOT NULL,
                                              `paused` tinyint(1) NOT NULL,
                                              `last_updated` bigint(20) NOT NULL,
                                              PRIMARY KEY (`projection_name`,`projection_key`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;

-- Create syntax for TABLE 'akka_projection_offset_store'
CREATE TABLE `akka_projection_offset_store` (
                                                `projection_name` varchar(255) NOT NULL,
                                                `projection_key` varchar(255) NOT NULL,
                                                `current_offset` varchar(255) NOT NULL,
                                                `manifest` varchar(255) NOT NULL DEFAULT '',
                                                `mergeable` tinyint(1) NOT NULL,
                                                `last_updated` bigint(20) NOT NULL,
                                                PRIMARY KEY (`projection_name`,`projection_key`),
                                                KEY `projection_name_index` (`projection_name`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;


CREATE TABLE `monthly_income_and_expense_summary` (
                                                `user_id` varchar(255) NOT NULL,
                                                `year` int(4) NOT NULL,
                                                `month` int(2) NOT NULL,
                                                `day` int(2) NOT NULL,
                                                `income` DECIMAL(19, 4) NOT NULL default 0,
                                                `expense` DECIMAL(19, 4) NOT NULL default 0,
                                                PRIMARY KEY (`user_id`,`year`, `month`)
) ENGINE=InnoDB DEFAULT CHARSET=latin1;