package com.workert.robotics.base.roboscriptbytecode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.workert.robotics.base.roboscriptbytecode.OpCode.*;
import static com.workert.robotics.base.roboscriptbytecode.Token.TokenType.*;

public final class Compiler {
	final RoboScript roboScriptInstance;
	Scanner scanner;
	Chunk chunk = new Chunk();
	Token current;
	Token previous;

	private final Map<String, Byte> globalVariableLookup = new HashMap<>();
	private final List<Local> locals = new ArrayList<>();
	private int scopeDepth = 0;

	Compiler(RoboScript roboScriptInstance) {
		this.roboScriptInstance = roboScriptInstance;
	}

	private Chunk getCurrentChunk() {
		return this.chunk;
	}


	void compile(String source) {
		System.out.println("Started compiling.");
		long timeBefore = System.currentTimeMillis();
		try {
			this.scanner = new Scanner(source);
			this.advance();
			while (!this.checkAndConsumeIfMatches(EOF)) {
				this.declaration();
			}
			this.endCompiler();
		} catch (CompileError e) {
			this.synchronize();
		}
		System.out.println("Compiled in " + (System.currentTimeMillis() - timeBefore) + "ms.");
	}

	private void endCompiler() {
		this.emitReturn();
	}


	private void expression() {
		this.parsePrecedence(Precedence.ASSIGNMENT);
	}

	private void expressionStatement() {
		this.expression();
		this.consumeOrThrow(SEMICOLON, "Expected ';' after expression.");
		this.emitByte(OP_POP);
	}


	private void declaration() {
		try {

			if (this.checkAndConsumeIfMatches(VAR)) {
				this.varDeclaration();
			} else {
				this.statement();
			}

		} catch (CompileError e) {
			this.synchronize();
		}

	}

	private void varDeclaration() {
		byte global = this.parseVariable("Expected variable name.");
		String place = this.previous.lexeme;
		if (this.globalVariableLookup.containsKey(place))
			throw this.error("Variable '" + place + "' already declared in the public scope.");

		if (this.checkAndConsumeIfMatches(EQUAL)) {
			this.expression();
		} else {
			this.emitByte(OP_NULL);
		}

		this.consumeOrThrow(SEMICOLON, "Expected ';' after variable declaration.");
		this.globalVariableLookup.put(place, global);
		this.defineVariable(global);

	}

	private void defineVariable(byte global) {
		if (this.scopeDepth > 0) {
			this.markInitialized();
			return;
		}

		// this.globalVariableLookup.put(this.previous.lexeme, global);
		this.emitBytes(OP_DEFINE_GLOBAL, global);
	}

	private void declareVariable() {
		if (this.scopeDepth == 0) return;
		Token name = this.previous;
		for (int i = this.locals.size() - 1; i >= 0; i--) {
			Local local = this.locals.get(i);
			if (local.depth != -1 && local.depth < this.scopeDepth) break;
			if (name.lexeme.equals(local.name.lexeme))
				throw this.error("A variable with the name '" + name.lexeme + "' already exists in the current scope");
		}
		this.addLocal(name);
	}

	void variable(boolean canAssign) {
		byte variable = this.resolveLocal(this.previous);
		if (variable != -1) { // inside a scope
			this.emitVariable(OP_GET_LOCAL, OP_SET_LOCAL, variable, canAssign);
		} else { // outside a scope; global
			if (this.globalVariableLookup.containsKey(this.previous.lexeme)) {
				variable = this.globalVariableLookup.get(this.previous.lexeme);
				this.emitVariable(OP_GET_GLOBAL, OP_SET_GLOBAL, variable, canAssign);
			} else
				throw this.error("Variable '" + this.previous.lexeme + "' has not been declared.");
		}
	}

	private void addLocal(Token name) {
		if (this.locals.size() == 256)
			throw this.error("Too many local variables in function.");
		this.locals.add(new Local(name, -1));
	}

	private byte resolveLocal(Token name) {
		for (int i = (this.locals.size() - 1); i >= 0; i--) {
			Local local = this.locals.get(i);
			if (name.lexeme.equals(local.name.lexeme)) {
				if (local.depth == -1) {
					throw this.error("Cannot read local variable in its own initializer.");
				}
				return (byte) i;
			}
		}
		return -1;
	}

	private void statement() {
		if (this.checkAndConsumeIfMatches(LEFT_BRACE)) {
			this.beginScope();
			this.block();
			this.endScope();
		} else if (this.checkAndConsumeIfMatches(IF)) {
			this.ifStatement();
		} else if (this.checkAndConsumeIfMatches(WHILE)) {
			this.whileStatement();
		} else if (this.checkAndConsumeIfMatches(FOR)) {
			this.forStatement();
		} else {
			this.expressionStatement();
		}
	}

	private void block() {
		while (!this.isNextToken(RIGHT_BRACE) && !this.isNextToken(EOF)) {
			this.declaration();
		}
		this.consumeOrThrow(RIGHT_BRACE, "Expected '}' after block.");
		System.out.println(this.current);
	}

	private void ifStatement() {
		this.consumeOrThrow(LEFT_PAREN, "Expected '(' after 'if'.");
		this.expression();
		this.consumeOrThrow(RIGHT_PAREN, "Expected ')' after condition.");
		int thenJump = this.emitJump(OP_JUMP_IF_FALSE);
		this.emitByte(OP_POP);
		this.statement();
		int elseJump = this.emitJump(OP_JUMP);
		this.patchJump(thenJump);
		this.emitByte(OP_POP);
		if (this.checkAndConsumeIfMatches(ELSE)) this.statement();
		this.patchJump(elseJump);
	}

	private void whileStatement() {
		int loopStart = this.getCurrentChunk().getCodeSize();
		this.consumeOrThrow(LEFT_PAREN, "Expected '(' after 'while'.");
		this.expression();
		this.consumeOrThrow(RIGHT_PAREN, "Expected ')' after condition.");
		int exitJump = this.emitJump(OP_JUMP_IF_FALSE);
		this.emitByte(OP_POP);
		this.statement();
		this.emitLoop(loopStart);
		this.patchJump(exitJump);
		this.emitByte(OP_POP);
	}

	private void forStatement() {
		this.beginScope();
		this.consumeOrThrow(LEFT_PAREN, "Expected '(' after 'for'.");
		if (!this.checkAndConsumeIfMatches(SEMICOLON)) {
			if (this.checkAndConsumeIfMatches(VAR)) {
				this.varDeclaration();
			} else {
				this.expressionStatement();
			}
		}

		int loopStart = this.getCurrentChunk().getCodeSize();
		int exitJump = -1;
		if (!this.checkAndConsumeIfMatches(SEMICOLON)) {
			this.expression();
			this.consumeOrThrow(SEMICOLON, "Expected ';' after loop condition.");
			exitJump = this.emitJump(OP_JUMP_IF_FALSE);
			this.emitByte(OP_POP);
		}
		if (!this.checkAndConsumeIfMatches(RIGHT_PAREN)) {
			int bodyJump = this.emitJump(OP_JUMP);
			int incrementStart = this.getCurrentChunk().getCodeSize();
			this.expression();
			this.emitByte(OP_POP);
			this.consumeOrThrow(RIGHT_PAREN, "Expect ')' after for clauses");
			this.emitLoop(loopStart);
			loopStart = incrementStart;
			this.patchJump(bodyJump);
		}
		this.statement();
		this.emitLoop(loopStart);
		if (exitJump != -1) {
			this.patchJump(exitJump);
			this.emitByte(OP_POP);
		}
		this.endScope();
	}

	private int emitJump(byte instruction) {
		this.emitByte(instruction);
		this.emitByte((byte) 0xFF);
		this.emitByte((byte) 0xFF);
		return this.getCurrentChunk().getCodeSize() - 2;
	}

	private void emitLoop(int loopStart) {
		this.emitByte(OP_LOOP);
		int offset = this.getCurrentChunk().getCodeSize() - loopStart + 2;
		if (offset > 65535) throw this.error("Loop body too large.");
		this.emitByte((byte) ((offset >> 8) & 0xff));
		this.emitByte((byte) (offset & 0xFF));
	}

	private void patchJump(int offset) {
		int jump = this.getCurrentChunk().getCodeSize() - offset - 2;
		if (jump > 65535) {
			throw this.error("Too much code to jump over.");
		}

		this.getCurrentChunk().setCode(offset, (byte) ((jump >> 8) & 0xff));
		this.getCurrentChunk().setCode(offset + 1, (byte) (jump & 0xFF));
	}


	private void emitVariable(byte getOp, byte setOp, byte lookup, boolean canAssign) {
		if (canAssign && this.checkAndConsumeIfMatches(EQUAL)) {
			this.expression();
			this.emitBytes(setOp, lookup);
		} else
			this.emitBytes(getOp, lookup);
	}


	void grouping(boolean canAssign) {
		this.expression();
		this.consumeOrThrow(RIGHT_PAREN, "Expected ')' after expression.");
	}


	void number(boolean canAssign) {
		double value = Double.parseDouble(this.previous.lexeme);
		this.emitConstant(value);
	}

	void literal(boolean canAssign) {
		switch (this.previous.type) {
			case FALSE -> this.emitByte(OP_FALSE);
			case TRUE -> this.emitByte(OP_TRUE);
			case STRING_VALUE -> this.emitConstant(this.previous.lexeme);
			case NULL -> this.emitByte(OP_NULL);
			default -> throw new IllegalArgumentException("Invalid literal operator type");
		}
	}

	void unary(boolean canAssign) {
		Token.TokenType operatorType = this.previous.type;
		this.parsePrecedence(Precedence.UNARY);
		switch (operatorType) {
			case MINUS -> this.emitByte(OP_NEGATE);
			case BANG -> this.emitByte(OP_NOT);
			default -> throw new IllegalArgumentException("Invalid unary operator type");
		}
	}

	void binary(boolean canAssign) {
		Token.TokenType operatorType = this.previous.type;
		ParseRule rule = operatorType.getParseRule();
		this.parsePrecedence(rule.precedence + 1);

		switch (operatorType) {
			case PLUS -> this.emitByte(OP_ADD);
			case MINUS -> this.emitByte(OP_SUBTRACT);
			case STAR -> this.emitByte(OP_MULTIPLY);
			case SLASH -> this.emitByte(OP_DIVIDE);
			case GREATER -> this.emitByte(OP_GREATER);
			case GREATER_EQUAL -> this.emitByte(OP_GREATER_EQUAL);
			case LESS -> this.emitByte(OP_LESS_EQUAL);
			case EQUAL_EQUAL -> this.emitByte(OP_EQUAL);
			case BANG_EQUAL -> this.emitByte(OP_NOT_EQUAL);
			default -> throw new IllegalArgumentException("Invalid binary operator type");
		}
	}

	void and(boolean canAssign) {
		int endJump = this.emitJump(OP_JUMP_IF_FALSE);
		this.emitByte(OP_POP);
		this.parsePrecedence(Precedence.AND);
		this.patchJump(endJump);
	}

	void or(boolean canAssign) {
		int elseJump = this.emitJump(OP_JUMP_IF_FALSE);
		int endJump = this.emitJump(OP_JUMP);

		this.patchJump(elseJump);
		this.emitByte(OP_POP);
		this.parsePrecedence(Precedence.OR);
		this.patchJump(endJump);
	}


	private void parsePrecedence(int precedence) {
		this.advance();
		ParseFunction prefixRule = this.previous.type.getParseRule().prefix;
		if (prefixRule == null) {
			throw this.error("Expected expression.");
		}
		boolean canAssign = precedence <= Precedence.ASSIGNMENT;
		prefixRule.apply(this, canAssign);
		while (precedence <= this.current.type.getParseRule().precedence) {
			this.advance();
			ParseFunction infixRule = this.previous.type.getParseRule().infix;
			infixRule.apply(this, canAssign);
		}

		if (canAssign && this.checkAndConsumeIfMatches(EQUAL)) {
			throw this.error("Invalid assignment target.");
		}
	}

	private byte parseVariable(String message) {
		this.consumeOrThrow(IDENTIFIER, message);
		this.declareVariable();
		if (this.scopeDepth > 0) return 0;
		byte variable = (byte) this.globalVariableLookup.size();
		return variable;
	}

	private void markInitialized() {
		if (this.scopeDepth == 0) return;
		this.locals.get(this.locals.size() - 1).depth = this.scopeDepth;
	}


	private void emitByte(byte b) {
		this.getCurrentChunk().writeCode(b, this.previous.line);
	}

	private void emitBytes(byte... b) {
		for (byte currentByte : b) {
			this.emitByte(currentByte);
		}
	}

	private void emitReturn() {
		this.emitByte(OP_RETURN);
	}

	private void emitConstant(Object value) {
		int constant = this.getCurrentChunk().addConstant(value);
		if (constant > 255) {
			throw this.error("Too many constants in one chunk.");
		}
		this.emitBytes(OP_CONSTANT, (byte) constant);
	}


	private void advance() {
		this.previous = this.current;

		this.current = this.scanner.scanToken();
		if (this.current.type == ERROR)
			throw this.errorAtCurrent(this.current.lexeme);

	}

	private void consumeOrThrow(Token.TokenType type, String message) {
		if (this.current.type == type) {
			this.advance();
			return;
		}
		throw this.errorAtCurrent(message);
	}

	private boolean checkAndConsumeIfMatches(Token.TokenType type) {
		if (this.current.type != type) return false;
		this.advance();
		return true;
	}

	private boolean isNextToken(Token.TokenType type) {
		return this.current.type == type;
	}


	private CompileError error(String message) {
		return this.errorAt(this.previous, message);
	}

	private CompileError errorAtCurrent(String message) {
		return this.errorAt(this.current, message);
	}

	private CompileError errorAt(Token token, String message) {
		String finalMessage = "Error";
		if (token.type == EOF) {
			finalMessage += " at end";
		} else /*if (token.type != ERROR)*/ {
			finalMessage += " at " + token.lexeme;
		}
		finalMessage += ": '" + message + "'";
		this.roboScriptInstance.reportCompileError(token.line, finalMessage);
		return new CompileError();
	}

	private void synchronize() {
		while (this.current.type != EOF) {
			if (this.previous.type == SEMICOLON) return;
			switch (this.current.type) {
				case CLASS, FUNCTION, VAR, FOR, IF, WHILE, RETURN -> {
					return;
				}
			}
			this.advance();
		}
	}

	private void beginScope() {
		this.scopeDepth++;
	}

	private void endScope() {
		this.scopeDepth--;
		while (this.locals.size() > 0 && this.locals.get(this.locals.size() - 1).depth > this.scopeDepth) {
			this.emitByte(OP_POP);
			this.locals.remove(this.locals.size() - 1);
		}
	}


	private boolean isAtEnd() {
		return this.checkAndConsumeIfMatches(EOF);
	}


	protected static class CompileError extends RuntimeException {
	}

	protected static class ParseRule {
		ParseFunction prefix;
		ParseFunction infix;
		byte precedence;


		ParseRule(ParseFunction prefix, ParseFunction infix, byte precedence) {
			this.prefix = prefix;
			this.infix = infix;
			this.precedence = precedence;
		}
	}

	private static class Local {
		Token name;
		int depth;

		Local(Token name, int depth) {
			this.name = name;
			this.depth = depth;
		}
	}

	protected interface Precedence {
		byte NONE = 0;
		byte ASSIGNMENT = 1; // =
		byte OR = 2; // or
		byte AND = 3; // and
		byte EQUALITY = 4; // == !=
		byte COMPARISON = 5; // < > <= >=
		byte TERM = 6; // + -
		byte FACTOR = 7; // * /
		byte UNARY = 8; // ! -
		byte CALL = 9; // . ()
		byte PRIMARY = 10;
	}


}
