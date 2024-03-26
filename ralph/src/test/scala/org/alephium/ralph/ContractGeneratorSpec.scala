// Copyright 2018 The Alephium Authors
// This file is part of the alephium project.
//
// The library is free software: you can redistribute it and/or modify
// it under the terms of the GNU Lesser General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// The library is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Lesser General Public License for more details.
//
// You should have received a copy of the GNU Lesser General Public License
// along with the library. If not, see <http://www.gnu.org/licenses/>.

package org.alephium.ralph

import org.alephium.util.AlephiumSpec

class ContractGeneratorSpec extends AlephiumSpec {
  trait Fixture {
    def defaultValue(tpe: Type): String = {
      tpe match {
        case Type.U256 | Type.I256               => "0"
        case Type.Bool                           => "false"
        case Type.ByteVec                        => "#00"
        case Type.Address                        => "@1C2RAVWSuaXw8xtUxqVERR7ChKBE1XgscNFw73NSHE1v3"
        case Type.Contract(id)                   => s"${id.name}(#00)"
        case Type.FixedSizeArray(baseType, size) => s"[${defaultValue(baseType)}; $size]"
        case _                                   => throw new RuntimeException("Invalid type")
      }
    }

    def genContract(code: String, tpe: Type): ContractGenerator.WrapperContract = {
      val multiContract = Compiler.compileMultiContract(code).rightValue
      val state         = Compiler.State.buildFor(multiContract, 0)(CompilerOptions.Default)
      ContractGenerator.genContract(state, tpe)
    }

    def test(code: String, tpe: Type, generatedContract: String) = {
      val generated = genContract(code, tpe)
      generated.sourceCode.filterNot(_.isWhitespace) is generatedContract.filterNot(_.isWhitespace)
      ContractGenerator.ContractsCache.nonEmpty is true
      ContractGenerator.clearCache()
    }
  }

  it should "generate contract for primitive type" in new Fixture {
    def code(tpe: Type, value: String): String =
      s"""
         |Contract Foo(mut map: Map[U256, ${ContractGenerator.getTypeSignature(tpe)}]) {
         |  pub fn f(address: Address) -> () {
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, $value)
         |  }
         |}
         |Contract Bar() {
         |  pub fn f() -> () {}
         |}
         |""".stripMargin

    def generatedCode(tpe: Type): String = {
      val typeSignature = ContractGenerator.getTypeSignature(tpe)
      s"""
         |Contract ContractWrapper${typeSignature}(mut value: $typeSignature,  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |  pub fn f1() -> $typeSignature {
         |    return value
         |  }
         |  @using(updateFields = true)
         |  pub fn f2(newValue: $typeSignature) -> () {
         |    f0(callerContractId!())
         |    value = newValue
         |  }
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }

    val types = Type.primitives :+ Type.Contract(Ast.TypeId("Bar"))
    types.foreach(t => test(code(t, defaultValue(t)), t, generatedCode(t)))
  }

  it should "generate contract for array type" in new Fixture {
    val code: String =
      s"""
         |Contract Foo(mut map: Map[U256, [U256; 2]]) {
         |  pub fn f(address: Address) -> () {
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, [0; 2])
         |  }
         |}
         |""".stripMargin

    val generatedCode: String = {
      s"""
         |Contract ContractWrapper_U256_2_(mut value: [U256;2],  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |  pub fn f1() -> [U256;2] {
         |    return value
         |  }
         |  @using(updateFields = true)
         |  pub fn f2(newValue: [U256;2]) -> () {
         |    f0(callerContractId!())
         |    value = newValue
         |  }
         |  pub fn f3(index0: U256) -> U256 {
         |    return value[index0]
         |  }
         |  @using(updateFields = true)
         |  pub fn f4(newValue: U256, index0: U256) -> () {
         |    f0(callerContractId!())
         |    value[index0] = newValue
         |  }
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }
    test(code, Type.FixedSizeArray(Type.U256, 2), generatedCode)
  }

  it should "generate contract for multidimensional array type" in new Fixture {
    val code: String =
      s"""
         |Contract Foo(mut map: Map[U256, [[U256; 2]; 3]]) {
         |  pub fn f(address: Address) -> () {
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, [[0; 2]; 3])
         |  }
         |}
         |""".stripMargin

    val generatedCode: String = {
      s"""
         |Contract ContractWrapper__U256_2__3_(mut value: [[U256;2];3],  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |  pub fn f1() -> [[U256;2];3] {
         |    return value
         |  }
         |  @using(updateFields = true)
         |  pub fn f2(newValue: [[U256;2];3]) -> () {
         |    f0(callerContractId!())
         |    value = newValue
         |  }
         |  pub fn f3(index0: U256) -> [U256;2] {
         |    return value[index0]
         |  }
         |  @using(updateFields = true)
         |  pub fn f4(newValue: [U256;2], index0: U256) -> () {
         |    f0(callerContractId!())
         |    value[index0] = newValue
         |  }
         |  pub fn f5(index0: U256, index1: U256) -> U256 {
         |    return value[index0][index1]
         |  }
         |  @using(updateFields = true)
         |  pub fn f6(newValue: U256, index0: U256, index1: U256) -> () {
         |    f0(callerContractId!())
         |    value[index0][index1] = newValue
         |  }
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }
    test(code, Type.FixedSizeArray(Type.FixedSizeArray(Type.U256, 2), 3), generatedCode)
  }

  it should "generate contract for struct array" in new Fixture {
    val code: String =
      s"""
         |struct Bar {
         |  mut x: U256,
         |  y: ByteVec
         |}
         |Contract Foo(mut map: Map[U256, [Bar; 2]]) {
         |  pub fn f(address: Address) -> () {
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, [Bar{x: 0, y: #00}; 2])
         |  }
         |}
         |""".stripMargin

    val generatedCode: String = {
      s"""
         |Contract ContractWrapper_Bar_2_(mut value: [Bar;2],  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |  pub fn f1() -> [Bar;2] {
         |    return value
         |  }
         |  pub fn f2(index0: U256) -> Bar {
         |    return value[index0]
         |  }
         |  pub fn f3(index0: U256) -> U256 {
         |    return value[index0].x
         |  }
         |  @using(updateFields = true)
         |  pub fn f4(newValue: U256, index0: U256) -> () {
         |    f0(callerContractId!())
         |    value[index0].x = newValue
         |  }
         |  pub fn f5(index0: U256) -> ByteVec {
         |    return value[index0].y
         |  }
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }
    test(code, Type.FixedSizeArray(Type.Struct(Ast.TypeId("Bar")), 2), generatedCode)
  }

  it should "generate contract for immutable struct type" in new Fixture {
    val code: String =
      s"""
         |struct Foo {
         |  mut x: U256,
         |  y: ByteVec
         |}
         |struct Bar {
         |  a: I256,
         |  mut b: [Foo; 2]
         |}
         |Contract Baz(mut map: Map[U256, [U256; 2]]) {
         |  pub fn f(address: Address) -> () {
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, [0; 2])
         |  }
         |}
         |""".stripMargin

    val generatedCode: String = {
      s"""
         |Contract ContractWrapperBar(mut value: Bar,  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |
         |  pub fn f1() -> Bar {
         |    return value
         |  }
         |
         |  pub fn f2() -> I256 {
         |    return value.a
         |  }
         |
         |  pub fn f3() -> [Foo;2] {
         |    return value.b
         |  }
         |
         |  pub fn f4(index0: U256) -> Foo {
         |    return value.b[index0]
         |  }
         |
         |  pub fn f5(index0: U256) -> U256 {
         |    return value.b[index0].x
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f6(newValue: U256, index0: U256) -> () {
         |    f0(callerContractId!())
         |    value.b[index0].x = newValue
         |  }
         |
         |  pub fn f7(index0: U256) -> ByteVec {
         |    return value.b[index0].y
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }
    test(code, Type.Struct(Ast.TypeId("Bar")), generatedCode)
  }

  it should "generate contract for mutable struct type" in new Fixture {
    val code: String =
      s"""
         |struct Foo {
         |  mut x: U256,
         |  mut y: ByteVec
         |}
         |struct Bar {
         |  mut a: I256,
         |  mut b: [Foo; 2]
         |}
         |Contract Baz(mut map: Map[U256, [U256; 2]]) {
         |  pub fn f(address: Address) -> () {
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, [0; 2])
         |  }
         |}
         |""".stripMargin

    val generatedCode: String = {
      s"""
         |Contract ContractWrapperBar(mut value: Bar,  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |
         |  pub fn f1() -> Bar {
         |    return value
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f2(newValue: Bar) -> () {
         |    f0(callerContractId!())
         |    value = newValue
         |  }
         |
         |  pub fn f3() -> I256 {
         |    return value.a
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f4(newValue: I256) -> () {
         |    f0(callerContractId!())
         |    value.a = newValue
         |  }
         |
         |  pub fn f5() -> [Foo;2] {
         |    return value.b
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f6(newValue: [Foo;2]) -> () {
         |    f0(callerContractId!())
         |    value.b = newValue
         |  }
         |
         |  pub fn f7(index0: U256) -> Foo {
         |    return value.b[index0]
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f8(newValue: Foo, index0: U256) -> () {
         |    f0(callerContractId!())
         |    value.b[index0] = newValue
         |  }
         |
         |  pub fn f9(index0: U256) -> U256 {
         |    return value.b[index0].x
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f10(newValue: U256, index0: U256) -> () {
         |    f0(callerContractId!())
         |    value.b[index0].x = newValue
         |  }
         |
         |  pub fn f11(index0: U256) -> ByteVec {
         |    return value.b[index0].y
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f12(newValue: ByteVec, index0: U256) -> () {
         |    f0(callerContractId!())
         |    value.b[index0].y = newValue
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }
    test(code, Type.Struct(Ast.TypeId("Bar")), generatedCode)
  }

  it should "generate contract for nested struct" in new Fixture {
    val code: String =
      s"""
         |struct Foo {
         |  mut x: U256,
         |  y: ByteVec
         |}
         |struct Bar {
         |  a: I256,
         |  mut b: [Foo; 2]
         |}
         |struct Baz {
         |  c: Bool,
         |  mut d: [Bar; 2]
         |}
         |Contract C(mut map: Map[U256, Baz]) {
         |  pub fn f(address: Address) -> () {
         |    let baz = Baz{c: false, d: [Bar{a: 0, b: [Foo{x: 0, y: #00}; 2]}; 2]}
         |    map.insert!{address -> ALPH: minimalContractDeposit!()}(0, baz)
         |  }
         |}
         |""".stripMargin

    val generatedCode: String = {
      s"""
         |Contract ContractWrapperBaz(mut value: Baz,  parentContractId: ByteVec) {
         |  fn f0(callerContractId: ByteVec) -> () {
         |    checkCaller!(callerContractId == parentContractId, 0)
         |  }
         |
         |  pub fn f1() -> Baz {
         |    return value
         |  }
         |
         |  pub fn f2() -> Bool {
         |    return value.c
         |  }
         |
         |  pub fn f3() -> [Bar;2] {
         |    return value.d
         |  }
         |
         |  pub fn f4(index0: U256) -> Bar {
         |    return value.d[index0]
         |  }
         |
         |  pub fn f5(index0: U256) -> I256 {
         |    return value.d[index0].a
         |  }
         |
         |  pub fn f6(index0: U256) -> [Foo;2] {
         |    return value.d[index0].b
         |  }
         |
         |  pub fn f7(index0: U256, index1: U256) -> Foo {
         |    return value.d[index0].b[index1]
         |  }
         |
         |  pub fn f8(index0: U256, index1: U256) -> U256 {
         |    return value.d[index0].b[index1].x
         |  }
         |
         |  @using(updateFields = true)
         |  pub fn f9(newValue: U256, index0: U256, index1: U256) -> () {
         |    f0(callerContractId!())
         |    value.d[index0].b[index1].x = newValue
         |  }
         |
         |  pub fn f10(index0: U256, index1: U256) -> ByteVec {
         |    return value.d[index0].b[index1].y
         |  }
         |
         |  @using(assetsInContract = true)
         |  pub fn destroy(address: Address) -> () {
         |    f0(callerContractId!())
         |    destroySelf!(address)
         |  }
         |}
         |""".stripMargin
    }
    test(code, Type.Struct(Ast.TypeId("Baz")), generatedCode)
  }

  it should "generate fresh one if the contract name has been used" in new Fixture {
    val code =
      s"""
         |Contract ContractWrapperU256(mut map: Map[U256, U256]) {
         |  pub fn f() -> () {
         |    map[0] = 0
         |  }
         |}
         |""".stripMargin

    val generated = genContract(code, Type.U256)
    generated.contract.ident.name is "ContractWrapperU256_"
  }
}
