// scala
import zio._
import zio.test._
import zio.test.Assertion._
import zio.prelude.Validation


case class Person(name: String, age: Int)

def validateName(name: String): Validation[String, String] =
  if (name.isEmpty) Validation.fail("Name was empty")
  else Validation.succeed(name)

def validateAge(age: Int): Validation[String, Int] =
  if (age <= 0) Validation.fail(s"Age $age was less than zero")
  else Validation.succeed(age)

def validatePerson(name: String, age: Int): Validation[String, Person] =
  Validation.validateWith(validateName(name), validateAge(age))(Person.apply)




object TestValidation extends ZIOSpecDefault {



  def spec = suite("TestValidation")(
    test("basic arithmetic") {
      validatePerson("", -13) match {
        case Validation.Failure(errs, _) =>
          assert(errs)(equalTo("Name was empty")) &&
          assert(errs)(equalTo("Age -13 was less than zero"))
        case Validation.Success(_,_) =>
          assertTrue(false)
      }
    },
    test("effectful computation") {
      for {
        v <- ZIO.succeed(42)
      } yield assert(v)(equalTo(42))
    }
  )
}
