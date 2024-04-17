package net.martinprobson.example.server.db.repository

import weaver.SimpleIOSuite
import net.martinprobson.example.common.model.User

object UserRepositoryTests extends SimpleIOSuite {

  test("deleteUser") {
    for {
      userRepository <- InMemoryUserRepository.empty
      _ <- userRepository.addUser(User(1, "User1", "Email1"))
      _ <- userRepository.addUser(User(2, "User2", "Email2"))
      _ <- userRepository.addUser(User(3, "User3", "Email3"))
      _ <- userRepository.deleteUser(3)
      count <- userRepository.countUsers
    } yield expect(count == 2)
  }

  test("addUser/getUser") {
    for {
      userRepository <- InMemoryUserRepository.empty
      u <- userRepository.addUser(User("User1", "Email1"))
      user <- userRepository.getUser(u.id)
    } yield expect(user.contains(User(1, "User1", "Email1")))
  }

}
