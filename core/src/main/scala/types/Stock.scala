package org.greensheep.tradingtk
package core.types

import java.time.LocalDate

case class Stock(
                symbol: String,
                companyName: String,
                ipoDate: LocalDate
                ){

}
