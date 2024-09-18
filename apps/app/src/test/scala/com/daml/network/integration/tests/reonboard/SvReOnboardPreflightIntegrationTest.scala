package com.daml.network.integration.tests.reonboard

import com.daml.network.environment.EnvironmentImpl
import com.daml.network.integration.EnvironmentDefinition
import com.daml.network.integration.tests.SpliceTests.SpliceTestConsoleEnvironment
import com.daml.network.integration.tests.FrontendIntegrationTestWithSharedEnvironment
import com.daml.network.integration.tests.runbook.{
  SvUiIntegrationTestUtil,
  PreflightIntegrationTestUtil,
}
import com.daml.network.sv.util.AnsUtil
import com.daml.network.util.{FrontendLoginUtil, SvFrontendTestUtil, WalletFrontendTestUtil}
import com.digitalasset.canton.integration.BaseEnvironmentDefinition
import com.digitalasset.canton.topology.PartyId
import org.scalatest.time.{Minute, Span}

class SvReOnboardPreflightIntegrationTest
    extends FrontendIntegrationTestWithSharedEnvironment("validator", "sv")
    with SvUiIntegrationTestUtil
    with SvFrontendTestUtil
    with PreflightIntegrationTestUtil
    with FrontendLoginUtil
    with WalletFrontendTestUtil {

  override lazy val resetRequiredTopologyState: Boolean = false

  override def environmentDefinition
      : BaseEnvironmentDefinition[EnvironmentImpl, SpliceTestConsoleEnvironment] =
    EnvironmentDefinition.preflightTopology(
      this.getClass.getSimpleName
    )

  protected def sv1ScanClient(implicit env: SpliceTestConsoleEnvironment) = scancl("sv1Scan")

  override implicit val patienceConfig: PatienceConfig = PatienceConfig(scaled(Span(1, Minute)))

  private val svWalletUrl = s"https://wallet.sv.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private val svUsername = s"admin@sv-dev.com"

  private val validatorWalletUrl =
    s"https://wallet.validator.${sys.env("NETWORK_APPS_ADDRESS")}/"
  private val validatorUsername = s"admin@validator.com"

  private val password = sys.env(s"SV_DEV_NET_WEB_UI_PASSWORD");

  "Validator create a transfer offer to the reonboarded SV" in { implicit env =>
    val (_, offboardedSvParty) = withFrontEnd("validator") { implicit webDriver =>
      actAndCheck(
        s"Logging in to wallet at ${validatorWalletUrl}", {
          completeAuth0LoginWithAuthorization(
            validatorWalletUrl,
            validatorUsername,
            password,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        "User is logged in and onboarded",
        _ => {
          userIsLoggedIn()
          val usdText = find(id("wallet-balance-usd")).value.text.trim
          usdText should not be "..."
          val usd = parseAmountText(usdText, "USD")
          usd should be >= BigDecimal("100000")

          val loggedInUser = seleniumText(find(id("logged-in-user")))
          val ansUtil = new AnsUtil(ansAcronym)
          if (loggedInUser.endsWith(ansUtil.entryNameSuffix)) {
            val entry = sv1ScanClient.lookupEntryByName(loggedInUser)
            PartyId.tryFromProtoPrimitive(entry.user)
          } else PartyId.tryFromProtoPrimitive(loggedInUser)
        },
      )
    }

    val (_, reonbardedSvParty) = withFrontEnd("sv") { implicit webDriver =>
      actAndCheck(
        s"Logging in to wallet at ${svWalletUrl}", {
          completeAuth0LoginWithAuthorization(
            svWalletUrl,
            svUsername,
            password,
            () => find(id("logout-button")) should not be empty,
          )
        },
      )(
        "User is logged in and onboarded and the amulets are recovered from offboarded SV",
        _ => {
          userIsLoggedIn()

          val loggedInEntry = seleniumText(find(id("logged-in-user")))
          loggedInEntry shouldBe s"da-helm-test-node.sv.$ansAcronym"

          val entry = sv1ScanClient.lookupEntryByName(loggedInEntry)
          PartyId.tryFromProtoPrimitive(entry.user)
        },
      )
    }

    reonbardedSvParty should not be offboardedSvParty

    withFrontEnd("validator") { implicit webDriver =>
      clue(s"Creating transfer offer for: $reonbardedSvParty") {
        createTransferOffer(
          reonbardedSvParty,
          BigDecimal("100000") / 0.005,
          90,
          "p2ptransfer",
        )
      }
    }

    withFrontEnd("sv") { implicit webDriver =>
      val acceptButton = eventually() {
        findAll(className("transfer-offer")).toSeq.headOption match {
          case Some(element) =>
            element.childWebElement(className("transfer-offer-accept"))
          case None => fail("failed to find transfer offer")
        }
      }

      actAndCheck(
        "Accept transfer offer", {
          click on acceptButton
          click on "navlink-transactions"
        },
      )(
        "Transfer appears in transactions log",
        _ => {
          val rows = findAll(className("tx-row")).toSeq
          val expectedRow = rows.filter { row =>
            val transaction = readTransactionFromRow(row)
            transaction.partyDescription.exists(_.contains(offboardedSvParty.toProtoPrimitive))
          }
          inside(expectedRow) { case Seq(tx) =>
            val transaction = readTransactionFromRow(tx)
            transaction.action should matchText("Received")
            transaction.ccAmount should beWithin(
              BigDecimal(20000000) - smallAmount,
              BigDecimal(20000000),
            )
            transaction.usdAmount should beWithin(
              BigDecimal(100000) - smallAmount,
              BigDecimal(100000),
            )
          }
        },
      )
    }
  }
}
