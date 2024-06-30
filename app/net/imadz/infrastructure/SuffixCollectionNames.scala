package net.imadz.infrastructure

import akka.contrib.persistence.mongodb.CanSuffixCollectionNames

class SuffixCollectionNames extends CanSuffixCollectionNames{
  override def getSuffixFromPersistenceId(persistenceId: String): String = persistenceId match {
    // in this example, we remove any leading "-test" string from persistenceId passed as parameter
    case str: String if str.contains("Encourage") => "encourage"
    // otherwise, we do not suffix our collection
    case _ => ""
  }

  override def validateMongoCharacters(input: String): String = {
    // According to mongoDB documentation,
    // forbidden characters in mongoDB collection names (Unix) are /\. "$
    // Forbidden characters in mongoDB collection names (Windows) are /\. "$*<>:|?
    // in this example, we replace each forbidden character with an underscore character
    val forbidden = List('/', '\\', '.', ' ', '\"', '$', '*', '<', '>', ':', '|', '?')

    input.map { c => if (forbidden.contains(c)) '_' else c }
  }
}
