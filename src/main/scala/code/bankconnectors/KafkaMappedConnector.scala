package code.bankconnectors

import java.text.SimpleDateFormat
import java.util.{Date, Locale, UUID}

import code.management.ImporterAPI.ImporterTransaction
import code.metadata.comments.MappedComment
import code.metadata.counterparties.Counterparties
import code.metadata.narrative.MappedNarrative
import code.metadata.tags.MappedTag
import code.metadata.transactionimages.MappedTransactionImage
import code.metadata.wheretags.MappedWhereTag
import code.model._
import code.model.dataAccess._
import code.sandbox.{CreateViewImpls, Saveable}
import code.tesobe.CashTransaction
import code.transactionrequests.MappedTransactionRequest
import code.transactionrequests.TransactionRequests.{TransactionRequestCharge, TransactionRequest, TransactionRequestBody, TransactionRequestChallenge}
import code.util.Helper
import code.views.Views
import net.liftweb.common._
import net.liftweb.json
import net.liftweb.mapper._
import net.liftweb.util.Helpers._


object KafkaMappedConnector extends Connector with CreateViewImpls with Loggable {

  var producer = new KafkaProducer()
  var consumer = new KafkaConsumer()
  type AccountType = KafkaBankAccount

  def getUser( username: String, password: String ): Box[KafkaUserImport] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Send request to Kafka, marked with reqId
    // so we can fetch the corresponding response
    val argList = Map( "email"    -> username.toLowerCase,
                       "password" -> password )
    implicit val formats = net.liftweb.json.DefaultFormats

    val r = KafkaMappedConnector.process(reqId, "getUser", argList).extract[KafkaValidatedUserImport]
    val recDisplayName = r.display_name
    val recEmail = r.email
    if (recEmail == username.toLowerCase && recEmail != "Not Found") {
      if (recDisplayName == "") {
        val user = new KafkaUserImport( recEmail, password, recEmail)
        Full(user)
      }
      else {
        val user = new KafkaUserImport(recEmail, password, recDisplayName)
        Full(user)
      }
    } else {
      // If empty result from Kafka return empty data
      Empty
    }
  }

  def setAccountOwner(owner : String, account: KafkaAccountImport) : Unit = {
    val apiUserOwner = APIUser.findAll.find(user => owner == user.emailAddress)
    apiUserOwner match {
      case Some(o) => {
        MappedAccountHolder.create
          .user(o)
          .accountBankPermalink(account.bank)
          .accountPermalink(account.id).save
      }
      case None => {
        //This shouldn't happen as OBPUser should generate the APIUsers when saved
        logger.error(s"api user(s) with email $owner not found.")
      }
    }
  }

  def updateUserAccountViews( apiUser: APIUser ) = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create argument list with reqId
    // in order to fetch corresponding response
    val argList = Map("username"  -> apiUser.email.get)
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val rList = process(reqId, "getUserAccounts", argList).extract[List[KafkaAccountImport]]
    val res = {
      for (r <- rList) yield {
        val views = createSaveableViews(r)
        views.foreach(_.save())
        views.map(_.value).filterNot(_.isPublic).foreach(v => {
          Views.views.vend.addPermission(v.uid, apiUser)
        })
        setAccountOwner(apiUser.email.get, r)
      }
    }
  }

  def updatePublicAccountViews( user: APIUser ): List[List[Saveable[ViewType]]] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create argument list with reqId
    // in order to fetch corresponding response
    val argList = Map("username"  -> user.email.get )
    implicit val formats = net.liftweb.json.DefaultFormats
    val rList = process(reqId, "getPublicAccounts", argList).extract[List[KafkaAccountImport]]
    val res = {
      for (r <- rList) yield {
        val views = createSaveableViews(r)
        views.foreach(_.save())
        views
      }
    }
    res
  }

  def createSaveableViews(acc : KafkaAccountImport) : List[Saveable[ViewType]] = {
    val bankId = BankId(acc.bank)
    val accountId = AccountId(acc.id)

    val ownerView = createSaveableOwnerView(bankId, accountId)

    val publicView =
      if(acc.generate_public_view) Some(createSaveablePublicView(bankId, accountId))
      else None

    val accountantsView =
      if(acc.generate_accountants_view) Some(createSaveableAccountantsView(bankId, accountId))
      else None

    val auditorsView =
      if(acc.generate_auditors_view) Some(createSaveableAuditorsView(bankId, accountId))
      else None

    List(Some(ownerView), publicView, accountantsView, auditorsView).flatten
  }

  //gets banks handled by this connector
  override def getBanks: List[Bank] = {
    // Generate random uuid to be used as request-response match id
    val reqId: String = UUID.randomUUID().toString
    // Create empty argument list
    val argList = Map( "username" -> OBPUser.getCurrentUserUsername )
    // Send request to Kafka, marked with reqId 
    // so we can fetch the corresponding response
    implicit val formats = net.liftweb.json.DefaultFormats
    val rList = process(reqId, "getBanks", argList).extract[List[KafkaBankImport]]
    // Loop through list of responses and create entry for each
    val res = { for ( r <- rList ) yield {
        new KafkaBank(r)
      }
    }
    // Return list of results
    res
  }

  // Gets bank identified by bankId
  override def getBank(id: BankId): Box[Bank] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create Kafka producer
    val producer: KafkaProducer = new KafkaProducer()
    // Create argument list
    val argList = Map(  "bankId" -> id.toString,
                        "username" -> OBPUser.getCurrentUserUsername )
    // Send request to Kafka, marked with reqId 
    // so we can fetch the corresponding response
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = process(reqId, "getBank", argList).extract[KafkaBankImport]
    // Return result
    Full(new KafkaBank(r))
  }

  // Gets transaction identified by bankid, accountid and transactionId 
  def getTransaction(bankId: BankId, accountID: AccountId, transactionId: TransactionId): Box[Transaction] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create argument list with reqId
    // in order to fetch corresponding response
    val argList = Map( "bankId" -> bankId.toString,
                       "username" -> OBPUser.getCurrentUserUsername,
                       "accountId" -> accountID.toString,
                       "transactionId" -> transactionId.toString )
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = process(reqId, "getTransaction", argList).extract[KafkaTransactionImport]
    // If empty result from Kafka return empty data 
    if (r.id == "") {
      return Failure(null)
    }
    //updateAccountTransactions(bankId, accountID)
    Full(createNewTransaction(r))
  }

  override def getTransactions(bankId: BankId, accountID: AccountId, queryParams: OBPQueryParam*): Box[List[Transaction]] = {
    val limit = queryParams.collect { case OBPLimit(value) => MaxRows[MappedTransaction](value) }.headOption
    val offset = queryParams.collect { case OBPOffset(value) => StartAt[MappedTransaction](value) }.headOption
    val fromDate = queryParams.collect { case OBPFromDate(date) => By_>=(MappedTransaction.tFinishDate, date) }.headOption
    val toDate = queryParams.collect { case OBPToDate(date) => By_<=(MappedTransaction.tFinishDate, date) }.headOption
    val ordering = queryParams.collect {
      //we don't care about the intended sort field and only sort on finish date for now
      case OBPOrdering(_, direction) =>
        direction match {
          case OBPAscending => OrderBy(MappedTransaction.tFinishDate, Ascending)
          case OBPDescending => OrderBy(MappedTransaction.tFinishDate, Descending)
        }
    }
    val optionalParams : Seq[QueryParam[MappedTransaction]] = Seq(limit.toSeq, offset.toSeq, fromDate.toSeq, toDate.toSeq, ordering.toSeq).flatten
    val mapperParams = Seq(By(MappedTransaction.bank, bankId.value), By(MappedTransaction.account, accountID.value)) ++ optionalParams

    val reqId: String = UUID.randomUUID().toString
    val argList = Map( "bankId" -> bankId.toString,
                       "username" -> OBPUser.getCurrentUserUsername,
                       "accountId" -> accountID.toString,
                       "queryParams" -> queryParams.toString )
    implicit val formats = net.liftweb.json.DefaultFormats
    val rList = process(reqId, "getTransactions", argList).extract[List[KafkaTransactionImport]]
    // Return blank if empty 
    if (rList.head.id == "") {
      return Failure(null)
    }
    // Populate fields and generate result
    val res = {
      for (r <- rList) yield {
        createNewTransaction(r)
      }
    }
    //updateAccountTransactions(bankId, accountID)
    return Full(res)
  }

  override def getBankAccountType(bankId: BankId, accountID: AccountId): Box[KafkaBankAccount] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create argument list with reqId
    // in order to fetch corresponding response
    val argList = Map("bankId" -> bankId.toString,
                      "username"  -> OBPUser.getCurrentUserUsername,
                      "accountId" -> accountID.value)
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = process(reqId, "getBankAccount", argList).extract[KafkaAccountImport]
    val res = new KafkaBankAccount(r)
    Full(res)
  }

  override def getBankAccounts(accts: List[(BankId, AccountId)]): List[KafkaBankAccount] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create argument list with reqId
    // in order to fetch corresponding response
    val argList = Map("bankIds" -> accts.map(a => a._1).mkString(","),
      "username"  -> OBPUser.getCurrentUserUsername,
      "accountIds" -> accts.map(a => a._2).mkString(","))
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = process(reqId, "getBankAccounts", argList).extract[List[KafkaAccountImport]]
    val res = r.map ( t => new KafkaBankAccount(t) )
    res
  }

  private def getAccountByNumber(bankId : BankId, number : String) : Box[AccountType] = {
    // Generate random uuid to be used as request-respose match id
    val reqId: String = UUID.randomUUID().toString
    // Create argument list with reqId
    // in order to fetch corresponding response
    val argList = Map("bankId" -> bankId.toString,
                      "username" -> OBPUser.getCurrentUserUsername,
                      "number" -> number)
    // Since result is single account, we need only first list entry
    implicit val formats = net.liftweb.json.DefaultFormats
    val r = process(reqId, "getBankAccount", argList).extract[KafkaAccountImport]
    val res = new KafkaBankAccount(r)
    Full(res)
  }


  /**
   *
   * refreshes transactions via hbci if the transaction info is sourced from hbci
   *
   *  Checks if the last update of the account was made more than one hour ago.
   *  if it is the case we put a message in the message queue to ask for
   *  transactions updates
   *
   *  It will be used each time we fetch transactions from the DB. But the test
   *  is performed in a different thread.
   */
  /*
  private def updateAccountTransactions(bankId : BankId, accountID : AccountId) = {

    for {
      bank <- getBank(bankId)
      account <- getBankAccountType(bankId, accountID)
    } {
      spawn{
        val useMessageQueue = Props.getBool("messageQueue.updateBankAccountsTransaction", false)
        val outDatedTransactions = Box!!account.lastUpdate match {
          case Full(l) => now after time(l.getTime + hours(Props.getInt("messageQueue.updateTransactionsInterval", 1)))
          case _ => true
        }
        //if(outDatedTransactions && useMessageQueue) {
        //  UpdatesRequestSender.sendMsg(UpdateBankAccount(account.number, bank.national_identifier.get))
        //}
      }
    }
  }
  */

  //gets the users who are the legal owners/holders of the account
  override def getAccountHolders(bankId: BankId, accountID: AccountId): Set[User] =
    MappedAccountHolder.findAll(
      By(MappedAccountHolder.accountBankPermalink, bankId.value),
      By(MappedAccountHolder.accountPermalink, accountID.value)).map(accHolder => accHolder.user.obj).flatten.toSet


  def getOtherBankAccount(thisAccountBankId : BankId, thisAccountId : AccountId, metadata : OtherBankAccountMetadata) : Box[OtherBankAccount] = {
    //because we don't have a db backed model for OtherBankAccounts, we need to construct it from an
    //OtherBankAccountMetadata and a transaction
    for { //find a transaction with this counterparty
      t <- MappedTransaction.find(
        By(MappedTransaction.bank, thisAccountBankId.value),
        By(MappedTransaction.account, thisAccountId.value),
        By(MappedTransaction.counterpartyAccountHolder, metadata.getHolder),
        By(MappedTransaction.counterpartyAccountNumber, metadata.getAccountNumber))
    } yield {
      new OtherBankAccount(
        //counterparty id is defined to be the id of its metadata as we don't actually have an id for the counterparty itself
        id = metadata.metadataId,
        label = metadata.getHolder,
        nationalIdentifier = t.counterpartyNationalId.get,
        swift_bic = None,
        iban = t.getCounterpartyIban(),
        number = metadata.getAccountNumber,
        bankName = t.counterpartyBankName.get,
        kind = t.counterpartyAccountKind.get,
        originalPartyBankId = thisAccountBankId,
        originalPartyAccountId = thisAccountId,
        alreadyFoundMetadata = Some(metadata)
      )
    }
  }

  // Get all counterparties related to an account
  override def getOtherBankAccounts(bankId: BankId, accountID: AccountId): List[OtherBankAccount] =
    Counterparties.counterparties.vend.getMetadatas(bankId, accountID).flatMap(getOtherBankAccount(bankId, accountID, _))

  // Get one counterparty related to a bank account
  override def getOtherBankAccount(bankId: BankId, accountID: AccountId, otherAccountID: String): Box[OtherBankAccount] =
    // Get the metadata and pass it to getOtherBankAccount to construct the other account.
    Counterparties.counterparties.vend.getMetadata(bankId, accountID, otherAccountID).flatMap(getOtherBankAccount(bankId, accountID, _))

  override def getPhysicalCards(user: User): Set[PhysicalCard] =
    Set.empty

  override def getPhysicalCardsForBank(bankId: BankId, user: User): Set[PhysicalCard] =
    Set.empty


  override def makePaymentImpl(fromAccount: AccountType, toAccount: AccountType, amt: BigDecimal, description : String): Box[TransactionId] = {
    val fromTransAmt = -amt //from account balance should decrease
    val toTransAmt = amt //to account balance should increase

    //we need to save a copy of this payment as a transaction in each of the accounts involved, with opposite amounts
    val sentTransactionId = saveTransaction(fromAccount, toAccount, fromTransAmt, description)
    saveTransaction(toAccount, fromAccount, toTransAmt, description)

    sentTransactionId
  }


  /**
   * Saves a transaction with amount @amt and counterparty @counterparty for account @account. Returns the id
   * of the saved transaction.
   */
  private def saveTransaction(account : AccountType, counterparty : BankAccount, amt : BigDecimal, description : String) : Box[TransactionId] = {

    val transactionTime = now
    val currency = account.currency


    //update the balance of the account for which a transaction is being created
    val newAccountBalance : Long = account.balance.toLong + Helper.convertToSmallestCurrencyUnits(amt, account.currency)
    //account.balance = newAccountBalance

    val mappedTransaction = MappedTransaction.create
      .bank(account.bankId.value)
      .account(account.accountId.value)
      .transactionType("sandbox-payment")
      .amount(Helper.convertToSmallestCurrencyUnits(amt, currency))
      .newAccountBalance(newAccountBalance)
      .currency(currency)
      .tStartDate(transactionTime)
      .tFinishDate(transactionTime)
      .description(description)
      .counterpartyAccountHolder(counterparty.accountHolder)
      .counterpartyAccountNumber(counterparty.number)
      .counterpartyAccountKind(counterparty.accountType)
      .counterpartyBankName(counterparty.bankName)
      .counterpartyIban(counterparty.iban.getOrElse(""))
      .counterpartyNationalId(counterparty.nationalIdentifier).saveMe

    Full(mappedTransaction.theTransactionId)
  }

  /*
    Transaction Requests
  */

  override def createTransactionRequestImpl(transactionRequestId: TransactionRequestId, transactionRequestType: TransactionRequestType,
                                            account : BankAccount, counterparty : BankAccount, body: TransactionRequestBody,
                                            status: String, charge: TransactionRequestCharge) : Box[TransactionRequest] = {
    val mappedTransactionRequest = MappedTransactionRequest.create
      .mTransactionRequestId(transactionRequestId.value)
      .mType(transactionRequestType.value)
      .mFrom_BankId(account.bankId.value)
      .mFrom_AccountId(account.accountId.value)
      .mBody_To_BankId(counterparty.bankId.value)
      .mBody_To_AccountId(counterparty.accountId.value)
      .mBody_Value_Currency(body.value.currency)
      .mBody_Value_Amount(body.value.amount)
      .mBody_Description(body.description)
      .mStatus(status)
      .mStartDate(now)
      .mEndDate(now).saveMe
    Full(mappedTransactionRequest).flatMap(_.toTransactionRequest)
  }

  override def saveTransactionRequestTransactionImpl(transactionRequestId: TransactionRequestId, transactionId: TransactionId): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
        case Full(tr: MappedTransactionRequest) => Full(tr.mTransactionIDs(transactionId.value).save)
        case _ => Failure("Couldn't find transaction request ${transactionRequestId}")
      }
  }

  override def saveTransactionRequestChallengeImpl(transactionRequestId: TransactionRequestId, challenge: TransactionRequestChallenge): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
      case Full(tr: MappedTransactionRequest) => Full{
        tr.mChallenge_Id(challenge.id)
        tr.mChallenge_AllowedAttempts(challenge.allowed_attempts)
        tr.mChallenge_ChallengeType(challenge.challenge_type).save
      }
      case _ => Failure(s"Couldn't find transaction request ${transactionRequestId} to set transactionId")
    }
  }

  override def saveTransactionRequestStatusImpl(transactionRequestId: TransactionRequestId, status: String): Box[Boolean] = {
    val mappedTransactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    mappedTransactionRequest match {
      case Full(tr: MappedTransactionRequest) => Full(tr.mStatus(status).save)
      case _ => Failure(s"Couldn't find transaction request ${transactionRequestId} to set status")
    }
  }


  override def getTransactionRequestsImpl(fromAccount : BankAccount) : Box[List[TransactionRequest]] = {
    val transactionRequests = MappedTransactionRequest.findAll(By(MappedTransactionRequest.mFrom_AccountId, fromAccount.accountId.value),
                                                               By(MappedTransactionRequest.mFrom_BankId, fromAccount.bankId.value))

    Full(transactionRequests.flatMap(_.toTransactionRequest))
  }

  override def getTransactionRequestImpl(transactionRequestId: TransactionRequestId) : Box[TransactionRequest] = {
    val transactionRequest = MappedTransactionRequest.find(By(MappedTransactionRequest.mTransactionRequestId, transactionRequestId.value))
    transactionRequest.flatMap(_.toTransactionRequest)
  }


  override def getTransactionRequestTypesImpl(fromAccount : BankAccount) : Box[List[TransactionRequestType]] = {
    //TODO: write logic / data access
    Full(List(TransactionRequestType("SANDBOX_TAN")))
  }

  /*
    Bank account creation
   */

  //creates a bank account (if it doesn't exist) and creates a bank (if it doesn't exist)
  //again assume national identifier is unique
  override def createBankAndAccount(bankName: String, bankNationalIdentifier: String, accountNumber: String, accountHolderName: String): (Bank, BankAccount) = {
    //don't require and exact match on the name, just the identifier
    val bank = MappedBank.find(By(MappedBank.national_identifier, bankNationalIdentifier)) match {
      case Full(b) =>
        logger.info(s"bank with id ${b.bankId} and national identifier ${b.nationalIdentifier} found")
        b
      case _ =>
        logger.info(s"creating bank with national identifier $bankNationalIdentifier")
        //TODO: need to handle the case where generatePermalink returns a permalink that is already used for another bank
        MappedBank.create
          .permalink(Helper.generatePermalink(bankName))
          .fullBankName(bankName)
          .shortBankName(bankName)
          .national_identifier(bankNationalIdentifier)
          .saveMe()
    }

    //TODO: pass in currency as a parameter?
    val account = createAccountIfNotExisting(bank.bankId, AccountId(UUID.randomUUID().toString), accountNumber, "EUR", 0L, accountHolderName)

    (bank, account)
  }

  //for sandbox use -> allows us to check if we can generate a new test account with the given number
  override def accountExists(bankId: BankId, accountNumber: String): Boolean = {
    getAccountByNumber(bankId, accountNumber) != null
  }

  //remove an account and associated transactions
  override def removeAccount(bankId: BankId, accountID: AccountId) : Boolean = {
    //delete comments on transactions of this account
    val commentsDeleted = MappedComment.bulkDelete_!!(
      By(MappedComment.bank, bankId.value),
      By(MappedComment.account, accountID.value)
    )

    //delete narratives on transactions of this account
    val narrativesDeleted = MappedNarrative.bulkDelete_!!(
      By(MappedNarrative.bank, bankId.value),
      By(MappedNarrative.account, accountID.value)
    )

    //delete narratives on transactions of this account
    val tagsDeleted = MappedTag.bulkDelete_!!(
      By(MappedTag.bank, bankId.value),
      By(MappedTag.account, accountID.value)
    )

    //delete WhereTags on transactions of this account
    val whereTagsDeleted = MappedWhereTag.bulkDelete_!!(
      By(MappedWhereTag.bank, bankId.value),
      By(MappedWhereTag.account, accountID.value)
    )

    //delete transaction images on transactions of this account
    val transactionImagesDeleted = MappedTransactionImage.bulkDelete_!!(
      By(MappedTransactionImage.bank, bankId.value),
      By(MappedTransactionImage.account, accountID.value)
    )

    //delete transactions of account
    val transactionsDeleted = MappedTransaction.bulkDelete_!!(
      By(MappedTransaction.bank, bankId.value),
      By(MappedTransaction.account, accountID.value)
    )

    //remove view privileges (get views first)
    val views = ViewImpl.findAll(
      By(ViewImpl.bankPermalink, bankId.value),
      By(ViewImpl.accountPermalink, accountID.value)
    )

    //loop over them and delete
    var privilegesDeleted = true
    views.map (x => {
      privilegesDeleted &&= ViewPrivileges.bulkDelete_!!(By(ViewPrivileges.view, x.id_))
    })

    //delete views of account
    val viewsDeleted = ViewImpl.bulkDelete_!!(
      By(ViewImpl.bankPermalink, bankId.value),
      By(ViewImpl.accountPermalink, accountID.value)
    )

    //delete account
    val account = getBankAccountType(bankId, accountID)

    val accountDeleted = account match {
      case acc => true //acc.delete_! //TODO
      case _ => false
    }

    commentsDeleted && narrativesDeleted && tagsDeleted && whereTagsDeleted && transactionImagesDeleted &&
      transactionsDeleted && privilegesDeleted && viewsDeleted && accountDeleted
}

  //creates a bank account for an existing bank, with the appropriate values set. Can fail if the bank doesn't exist
  override def createSandboxBankAccount(bankId: BankId, accountID: AccountId, accountNumber: String,
                                        currency: String, initialBalance: BigDecimal, accountHolderName: String): Box[BankAccount] = {

    for {
      bank <- getBank(bankId) //bank is not really used, but doing this will ensure account creations fails if the bank doesn't
    } yield {

      val balanceInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(initialBalance, currency)
      createAccountIfNotExisting(bankId, accountID, accountNumber, currency, balanceInSmallestCurrencyUnits, accountHolderName)
    }

  }

  //sets a user as an account owner/holder
  override def setAccountHolder(bankAccountUID: BankAccountUID, user: User): Unit = {
    MappedAccountHolder.create
      .accountBankPermalink(bankAccountUID.bankId.value)
      .accountPermalink(bankAccountUID.accountId.value)
      .user(user.apiId.value)
      .save
  }

  private def createAccountIfNotExisting(bankId: BankId, accountID: AccountId, accountNumber: String,
                            currency: String, balanceInSmallestCurrencyUnits: Long, accountHolderName: String) : BankAccount = {
    getBankAccountType(bankId, accountID) match {
      case Full(a) =>
        logger.info(s"account with id $accountID at bank with id $bankId already exists. No need to create a new one.")
        a
      case _ => null //TODO
        /*
       new  KafkaBankAccount
          .bank(bankId.value)
          .theAccountId(accountID.value)
          .accountNumber(accountNumber)
          .accountCurrency(currency)
          .accountBalance(balanceInSmallestCurrencyUnits)
          .holder(accountHolderName)
          .saveMe()
          */
    }
  }

  /*
    End of bank account creation
   */

  /*
      Cash api
     */

  //cash api requires getting an account via a uuid: for legacy reasons it does not use bankId + accountID
  override def getAccountByUUID(uuid: String): Box[AccountType] = {
    getBankAccountType(null, AccountId(uuid))
  }

  //cash api requires a call to add a new transaction and update the account balance
  override def addCashTransactionAndUpdateBalance(account: AccountType, cashTransaction: CashTransaction): Unit = {

    val currency = account.currency
    val currencyDecimalPlaces = Helper.currencyDecimalPlaces(currency)

    //not ideal to have to convert it this way
    def doubleToSmallestCurrencyUnits(x : Double) : Long = {
      (x * math.pow(10, currencyDecimalPlaces)).toLong
    }

    //can't forget to set the sign of the amount cashed on kind being "in" or "out"
    //we just assume if it's not "in", then it's "out"
    val amountInSmallestCurrencyUnits = {
      if(cashTransaction.kind == "in") doubleToSmallestCurrencyUnits(cashTransaction.amount)
      else doubleToSmallestCurrencyUnits(-1 * cashTransaction.amount)
    }

    val currentBalanceInSmallestCurrencyUnits = account.balance
    val newBalanceInSmallestCurrencyUnits = currentBalanceInSmallestCurrencyUnits + amountInSmallestCurrencyUnits

    //create transaction
    val transactionCreated = MappedTransaction.create
      .bank(account.bankId.value)
      .account(account.accountId.value)
      .transactionType("cash")
      .amount(amountInSmallestCurrencyUnits)
      .newAccountBalance(newBalanceInSmallestCurrencyUnits.toLong)
      .currency(account.currency)
      .tStartDate(cashTransaction.date)
      .tFinishDate(cashTransaction.date)
      .description(cashTransaction.label)
      .counterpartyAccountHolder(cashTransaction.otherParty)
      .counterpartyAccountKind("cash")
      .save

    if(!transactionCreated) {
      logger.warn("Failed to save cash transaction")
    } else {
      //update account
      val accountUpdated = false //account.balance = newBalanceInSmallestCurrencyUnits

      if(!accountUpdated)
        logger.warn("Failed to update account balance after new cash transaction")
    }
  }

  /*
    End of cash api
   */

  /*
    Transaction importer api
   */

  //used by the transaction import api
  override def updateAccountBalance(bankId: BankId, accountID: AccountId, newBalance: BigDecimal): Boolean = {

    //this will be Full(true) if everything went well
    val result = for {
      acc <- getBankAccountType(bankId, accountID)
      bank <- getBank(bankId)
    } yield {
      //acc.balance = newBalance
      setBankAccountLastUpdated(bank.nationalIdentifier, acc.number, now)
    }

    result.getOrElse(false)
  }

  //transaction import api uses bank national identifiers to uniquely indentify banks,
  //which is unfortunate as theoretically the national identifier is unique to a bank within
  //one country
  private def getBankByNationalIdentifier(nationalIdentifier : String) : Box[Bank] = {
    MappedBank.find(By(MappedBank.national_identifier, nationalIdentifier))
  }


  private val bigDecimalFailureHandler : PartialFunction[Throwable, Unit] = {
    case ex : NumberFormatException => {
      logger.warn(s"could not convert amount to a BigDecimal: $ex")
    }
  }

  //used by transaction import api call to check for duplicates
  override def getMatchingTransactionCount(bankNationalIdentifier : String, accountNumber : String, amount: String, completed: Date, otherAccountHolder: String): Int = {
    //we need to convert from the legacy bankNationalIdentifier to BankId, and from the legacy accountNumber to AccountId
    val count = for {
      bankId <- getBankByNationalIdentifier(bankNationalIdentifier).map(_.bankId)
      account <- getAccountByNumber(bankId, accountNumber)
      amountAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(amount))
    } yield {

      val amountInSmallestCurrencyUnits =
        Helper.convertToSmallestCurrencyUnits(amountAsBigDecimal, account.currency)

      MappedTransaction.count(
        By(MappedTransaction.bank, bankId.value),
        By(MappedTransaction.account, account.accountId.value),
        By(MappedTransaction.amount, amountInSmallestCurrencyUnits),
        By(MappedTransaction.tFinishDate, completed),
        By(MappedTransaction.counterpartyAccountHolder, otherAccountHolder))
    }

    //icky
    count.map(_.toInt) getOrElse 0
  }

  //used by transaction import api
  override def createImportedTransaction(transaction: ImporterTransaction): Box[Transaction] = {
    //we need to convert from the legacy bankNationalIdentifier to BankId, and from the legacy accountNumber to AccountId
    val obpTransaction = transaction.obp_transaction
    val thisAccount = obpTransaction.this_account
    val nationalIdentifier = thisAccount.bank.national_identifier
    val accountNumber = thisAccount.number
    for {
      bank <- getBankByNationalIdentifier(transaction.obp_transaction.this_account.bank.national_identifier) ?~!
        s"No bank found with national identifier $nationalIdentifier"
      bankId = bank.bankId
      account <- getAccountByNumber(bankId, accountNumber)
      details = obpTransaction.details
      amountAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(details.value.amount))
      newBalanceAsBigDecimal <- tryo(bigDecimalFailureHandler)(BigDecimal(details.new_balance.amount))
      amountInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(amountAsBigDecimal, account.currency)
      newBalanceInSmallestCurrencyUnits = Helper.convertToSmallestCurrencyUnits(newBalanceAsBigDecimal, account.currency)
      otherAccount = obpTransaction.other_account
      mappedTransaction = MappedTransaction.create
        .bank(bankId.value)
        .account(account.accountId.value)
        .transactionType(details.kind)
        .amount(amountInSmallestCurrencyUnits)
        .newAccountBalance(newBalanceInSmallestCurrencyUnits)
        .currency(account.currency)
        .tStartDate(details.posted.`$dt`)
        .tFinishDate(details.completed.`$dt`)
        .description(details.label)
        .counterpartyAccountNumber(otherAccount.number)
        .counterpartyAccountHolder(otherAccount.holder)
        .counterpartyAccountKind(otherAccount.kind)
        .counterpartyNationalId(otherAccount.bank.national_identifier)
        .counterpartyBankName(otherAccount.bank.name)
        .counterpartyIban(otherAccount.bank.IBAN)
        .saveMe()
      transaction <- mappedTransaction.toTransaction(account)
    } yield transaction
  }

  override def setBankAccountLastUpdated(bankNationalIdentifier: String, accountNumber : String, updateDate: Date) : Boolean = {
    val result = for {
      bankId <- getBankByNationalIdentifier(bankNationalIdentifier).map(_.bankId)
      account <- getAccountByNumber(bankId, accountNumber)
    } yield {
        val acc = getBankAccountType(bankId, account.accountId)
        acc match {
          case a => true //a.lastUpdate = updateDate //TODO
          case _ => logger.warn("can't set bank account.lastUpdated because the account was not found"); false
        }
    }
    result.getOrElse(false)
  }

  /*
    End of transaction importer api
   */


  override def updateAccountLabel(bankId: BankId, accountID: AccountId, label: String): Boolean = {
    //this will be Full(true) if everything went well
    val result = for {
      acc <- getBankAccountType(bankId, accountID)
      bank <- getBank(bankId)
    } yield {
        //acc.label = label
      true
      }

    result.getOrElse(false)
  }




  /////////////////////////////////////////////////////////////////////////////



  def process(reqId: String, command: String, argList: Map[String,String]): json.JValue = { //List[Map[String,String]] = {
  var retries:Int = 3
    while (consumer == null && retries > 0 ) {
      retries-=1
      consumer = new KafkaConsumer()
    }
    retries = 3
    while (producer == null && retries > 0) {
      retries-=1
      producer = new KafkaProducer()
    }
    if (producer == null || consumer == null)
      return json.parse("""{"error":"connection failed. try again later."}""")
    // Send request to Kafka
    producer.send(reqId, command, argList, "1")
    // Request sent, now we wait for response with the same reqId
    val res = consumer.getResponse(reqId)
    res
  }


  // Helper for creating a transaction
  def createNewTransaction(r: KafkaTransactionImport) = {
    var datePosted: Date = null
    if (r.details.posted != null && r.details.posted.matches("^[0-9]{8}$"))
      datePosted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.details.posted)

    var dateCompleted: Date = null
    if (r.details.completed != null && r.details.completed.matches("^[0-9]{8}$"))
      dateCompleted = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(r.details.completed)

    val o = getBankAccountType(BankId(r.this_account.bank), AccountId(r.counterparty.get.account_number.get)).get
    //creates a dummy OtherBankAccount without an OtherBankAccountMetadata, which results in one being generated (in OtherBankAccount init)
    val dummyOtherBankAccount = createOtherBankAccount(o, None)
    //and create the proper OtherBankAccount with the correct "id" attribute set to the metadataId of the OtherBankAccountMetadata object
    //note: as we are passing in the OtherBankAccountMetadata we don't incur another db call to get it in OtherBankAccount init
    val otherAccount = createOtherBankAccount(o, Some(dummyOtherBankAccount.metadata))
    // Create new transaction
    new Transaction(
      r.id,                                                   // uuid:String
      TransactionId(r.id),                                    // id:TransactionId
      getBankAccountType( BankId(r.this_account.bank),
                          AccountId(r.this_account.id)).get,  // thisAccount:BankAccount
      otherAccount,                                           // otherAccount:OtherBankAccount
      r.details.`type`,                                       // transactionType:String
      BigDecimal(r.details.value),                            // val amount:BigDecimal
      "GBP",                                                  // currency:String
      Some(r.details.description),                            // description:Option[String]
      datePosted,                                             // startDate:Date
      dateCompleted,                                          // finishDate:Date
      BigDecimal(r.details.new_balance)                       // balance:BigDecimal
    )
  }


  case class KafkaBank(r: KafkaBankImport) extends Bank {
    def fullName           = r.full_name
    def shortName          = r.short_name
    def logoUrl            = r.logo
    def bankId             = BankId(r.id)
    def nationalIdentifier = "None"  //TODO
    def swiftBic           = "None"  //TODO
    def websiteUrl         = r.website
  }

  // Helper for creating other bank account
  def createOtherBankAccount(r: KafkaBankAccount, alreadyFoundMetadata : Option[OtherBankAccountMetadata]) = {
    new OtherBankAccount(
      id = alreadyFoundMetadata.map(_.metadataId).getOrElse(""),
      label = r.label,
      nationalIdentifier = r.nationalIdentifier, //TODO
      swift_bic = Some(r.swift_bic.get),              //TODO
      iban = Some(r.iban.get),
      number = r.number,
      bankName = r.bankName,
      kind = r.accountType,
      originalPartyBankId = new BankId(r.bankId.value),
      originalPartyAccountId = new AccountId(r.accountId.value),
      alreadyFoundMetadata = alreadyFoundMetadata
    )
  }

  case class KafkaBankAccount(r: KafkaAccountImport) extends BankAccount {
    def uuid : String               = r.id
    def accountId : AccountId       = AccountId(r.id)
    def accountType : String        = r.`type`
    def balance : BigDecimal        = BigDecimal(r.balance.amount)
    def currency : String           = r.balance.currency
    def name : String               = r.owners.head
    def label : String              = r.label
    def swift_bic : Option[String]  = Some("swift_bic") //TODO
    def iban : Option[String]       = Some(r.IBAN)
    def number : String             = r.number
    def bankId : BankId             = BankId(r.bank)
    def lastUpdate : Date           = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ENGLISH).parse(today.getTime.toString)
    def accountHolder : String      = r.owners.head
  }


  case class KafkaBankImport(
                              id : String,
                              short_name : String,
                              full_name : String,
                              logo : String,
                              website : String)


  // Branches to be imported must match this pattern
  case class KafkaBranchImport(
                                id : String,
                                bank_id: String,
                                name : String,
                                address : KafkaAddressImport,
                                location : KafkaLocationImport,
                                meta : KafkaMetaImport,
                                lobby : Option[KafkaLobbyImport],
                                driveUp : Option[KafkaDriveUpImport])

  case class KafkaLicenseImport(
                                 id : String,
                                 name : String)

  case class KafkaMetaImport(
                              license : KafkaLicenseImport)

  case class KafkaLobbyImport(
                               hours : String)

  case class KafkaDriveUpImport(
                                 hours : String)

  case class KafkaAddressImport(
                                 line_1 : String,
                                 line_2 : String,
                                 line_3 : String,
                                 city : String,
                                 county : String, // Division of State
                                 state : String, // Division of Country
                                 post_code : String,
                                 country_code: String)

  case class KafkaLocationImport(
                                  latitude : Double,
                                  longitude : Double)

  case class KafkaUserImport(
                              email : String,
                              password : String,
                              display_name : String)

  case class KafkaValidatedUserImport(
                                       email : String,
                                       display_name : String)

  case class KafkaAccountImport(
                                 id : String,
                                 bank : String,
                                 label : String,
                                 number : String,
                                 `type` : String,
                                 balance : KafkaBalanceImport,
                                 IBAN : String,
                                 owners : List[String],
                                 generate_public_view : Boolean,
                                 generate_accountants_view : Boolean,
                                 generate_auditors_view : Boolean)

  case class KafkaBalanceImport(
                                 currency : String,
                                 amount : String)

  case class KafkaTransactionImport(
                                     id : String,
                                     this_account : KafkaAccountIdImport,
                                     counterparty : Option[KafkaTransactionCounterparty],
                                     details : KafkaAccountDetailsImport)

  case class KafkaTransactionCounterparty(
                                           name : Option[String],  // Also known as Label
                                           account_number : Option[String])

  case class KafkaAccountIdImport(
                                   id : String,
                                   bank : String)

  case class KafkaAccountDetailsImport(
                                        `type` : String,
                                        description : String,
                                        posted : String,
                                        completed : String,
                                        new_balance : String,
                                        value : String)


  case class KafkaAtmImport(
                             id : String,
                             bank_id: String,
                             name : String,
                             address : KafkaAddressImport,
                             location : KafkaLocationImport,
                             meta : KafkaMetaImport
                           )


  case class KafkaProductImport(
                                 bank_id : String,
                                 code: String,
                                 name : String,
                                 category : String,
                                 family : String,
                                 super_family : String,
                                 more_info_url : String,
                                 meta : KafkaMetaImport
                               )


  case class KafkaAccountDataImport(
                                     banks : List[KafkaBankImport],
                                     users : List[KafkaUserImport],
                                     accounts : List[KafkaAccountImport]
                                   )

  case class KafkaDataImport(
                              banks : List[KafkaBankImport],
                              users : List[KafkaUserImport],
                              accounts : List[KafkaAccountImport],
                              transactions : List[KafkaTransactionImport],
                              branches: List[KafkaBranchImport],
                              atms: List[KafkaAtmImport],
                              products: List[KafkaProductImport],
                              crm_events: List[KafkaCrmEventImport]
                            )


  case class KafkaCrmEventImport (
                                   id : String, // crmEventId
                                   bank_id : String,
                                   customer: KafkaCustomerImport,
                                   category : String,
                                   detail : String,
                                   channel : String,
                                   actual_date: String
                                 )

  case class KafkaCustomerImport (
                                   name: String,
                                   number : String // customer number, also known as ownerId (owner of accounts) aka API User?
                                 )

}

